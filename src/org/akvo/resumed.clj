;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/

(ns org.akvo.resumed
  (:require [clojure.core.cache :as cache]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File FileOutputStream ByteArrayOutputStream]
           java.util.UUID
           javax.xml.bind.DatatypeConverter))

(def tus-headers
  {"Tus-Resumable" "1.0.0"
   "Tus-Version" "1.0.0"
   "Tus-Extension" "creation"})

(defn gen-id []
  (.replaceAll (str (UUID/randomUUID)) "-" ""))

(defn options-headers []
  (select-keys tus-headers ["Tus-Version" "Tus-Extension" "Tus-Max-Size"]))

(defn get-header [req header]
  (get-in req [:headers (.toLowerCase ^String header)]))

(defn to-number
  "Returns a numeric representation of a String.
  Returns -1 on unparseable String, blank or nil"
  [s]
  (if (not (str/blank? s))
    (try
      (Long/valueOf ^String s)
      (catch Exception _
        -1))
    -1))

(defmulti handle-request
  (fn [req opts]
    (:request-method req)))

(defmethod handle-request :default
  [req opts]
  {:status 400})

(defmethod handle-request :options
  [req {:keys [max-upload-size]}]
  {:status 204
   :headers (assoc (options-headers) "Tus-Max-Size" (str max-upload-size))})

(defn id-from-url [url]
  (last (str/split url #"/")))

(defmethod handle-request :head
  [req {:keys [save-path upload-cache]}]
  (let [upload-id (id-from-url (:uri req))]
    (if-let [found (cache/lookup @upload-cache upload-id)]
      {:status 200
       :headers (assoc tus-headers
                       "Upload-Offset" (str (:offset found))
                       "Upload-Length" (str (:length found))
                       "Upload-Metadata" (:metadata found)
                       "Cache-Control" "no-cache")}
      {:status 404
       :body "Not Found"
       :headers {"Cache-Control" "no-cache"}})))

(defn patch
  [req {:keys [save-path upload-cache]}]
  (let [id (id-from-url (:uri req))
        found (cache/lookup @upload-cache id)]
    (if found
      (let [rlen (-> req (get-header "content-length") to-number)
            off (-> req (get-header "upload-offset") to-number)
            ct (get-header req "content-type")
            tmp (ByteArrayOutputStream.)
            _ (io/copy (:body req) tmp)
            len (.size tmp)]
        (cond
          (not= "application/offset+octet-stream" ct) {:status 400
                                                       :body "Bad request: Content-Type must be application/offset+octet-stream"}
          (not= (:offset found) off) {:status 409
                                      :body "Conflict: Wrong Upload-Offset"}

          (and (not= -1 rlen)
               (not= rlen len)) {:status 400
                                 :body "Bad request: Request body size doesn't match with Content-Length header"}

          (or (> len (:length found))
              (> (+ len (:offset found)) (:length found))) {:status 413
                                                            :body (format "Body size exceeds the %s bytes allowed"
                                                                          (:length found))}

          :else (with-open [fos (FileOutputStream. ^String (:file found) true)]
                  (.write fos (.toByteArray tmp))
                  (let [len (.size tmp)
                        new-uploads (swap! upload-cache update-in [id :offset] + len)]
                    {:status 204
                     :headers (assoc tus-headers
                                     "Upload-Offset" (str (get-in new-uploads [id :offset])))}))))
      {:status 404
       :body "Not Found"})))

(defmethod handle-request :patch
  [req opts]
  (patch req opts))


(defn get-filename
  "Returns a file name decoding a base64 string of
  Upload-Metadata header.
  Attribution: http://stackoverflow.com/a/2054226"
  [s]
  (when-not (str/blank? s)
    (let [m (->> (str/split s #",")
                 (map #(str/split % #" "))
                 (into {}))]
      (when-let [filename (get m "filename")]
        (-> filename
            (DatatypeConverter/parseBase64Binary)
            (String.))))))

(defn get-location
  "Get Location string from request"
  [req]
  (let [origin (get-header req "origin")
        f-host (or (get-header req "x-forwarded-host")
                   (get-header req "host"))
        f-proto (get-header req "x-forwarded-proto")
        uri (:uri req)]
    (if (and (not (str/blank? f-host)) (not (str/blank? f-proto)))
      (format "%s://%s%s" f-proto f-host uri)
      (if (not (str/blank? origin))
        (format "%s%s" origin uri)
        (format "%s://%s%s%s"
                (name (:scheme req))
                (:server-name req)
                (if (and (not= (:server-port req) 80)
                         (not= (:server-port req) 443))
                  (str ":" (:server-port req))
                  "")
                uri)))))

(defn post
  [req {:keys [save-path upload-cache max-upload-size]}]
  (let [len (-> req (get-header "upload-length") to-number)]
    (cond
      (neg? len) {:status 400
                  :body "Bad Request"}

      (> len max-upload-size) {:status 413
                               :body "Request Entity Loo Large"}

      :else (let [id (gen-id)
                  um (get-header req "upload-metadata")
                  fname (or (get-filename um)  "file")
                  path (File. ^String save-path ^String id)
                  f (File. ^File path ^String fname)]
              (.mkdirs path)
              (.createNewFile f)
              (swap! upload-cache assoc id {:offset 0
                                            :file (.getAbsolutePath f)
                                            :length len
                                            :metadata um})
              {:status 201
               :headers {"Location" (str (get-location req) "/" id)
                         "Upload-Length" (str len)
                         "Upload-Metadata" um}}))))

(defmethod handle-request :post
  [req opts]
  (let [method-override (get-header req "x-http-method-override")]
    (if (= method-override "PATCH")
      (patch req opts)
      (post req opts))))

(defn save-path [save-dir]
  (str (or save-dir (System/getProperty "java.io.tmpdir")) "/resumed"))

(defn make-handler
  "Returns a ring handler capable of responding to client requests from
  a `tus` client. An optional map with configuration can be used
  {:save-dir \"/path/to/save/dir\"} defaults to `java.io.tmpdir`"
  [& [opts]]
  (let [save-path (save-path (:save-dir opts))
        upload-cache (atom (or (:upload-cache opts)
                               (cache/fifo-cache-factory {} :threshold 250)))
        max-upload-size (* 1024
                           1024
                           (or (:max-upload-size opts) 50))]
    (fn [req]
      (handle-request req {:save-path save-path
                           :upload-cache upload-cache
                           :max-upload-size max-upload-size}))))

(defn file-for-upload
  "Given a save-dir and a file upload url, it returns the java.io.File that was uploaded"
  [save-dir uri]
  (let [id (id-from-url uri)
        upload-path (str (save-path save-dir) "/" id)]
    (when-not (re-matches #"[a-zA-Z0-9-]+" id)
      (throw (ex-info "Invalid file" {:filename id})))
    (-> upload-path
        io/file
        .listFiles
        first)))
