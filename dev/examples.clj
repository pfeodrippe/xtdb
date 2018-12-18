(ns examples)

;; tag::start-system[]
(require '[crux.api :as api])

(def system (api/start-standalone-system {:kv-backend "crux.kv.memdb.MemKv"}))
;; end::start-system[]

;; tag::close-system[]
(.close system)
;; end::close-system[]

;; tag::submit-tx[]
(require '[crux.db :as db])

(db/submit-tx
 (:tx-log system)
 [[:crux.tx/put :http://dbpedia.org/resource/Pablo_Picasso
   {:crux.db/id :http://dbpedia.org/resource/Pablo_Picasso
    :name "Pablo"
    :last-name "Picasso"}
   #inst "2018-05-18T09:20:27.966-00:00"]])
;; end::submit-tx[]

;; tag::query[]
(require '[crux.query :as query])

(q/q (q/db (:kv-store system))
     '{:find [e]
       :where [[e :name "Pablo"]]})
;; end::query[]

(comment
  ;; tag::should-get[]
  #{[:http://dbpedia.org/resource/Pablo_Picasso]}
  ;; end::should-get[]
  )

;; tag::ek-example[]
(require '[crux.kafka.embedded :as ek])

(def storage-dir "dev-storage")
(def embedded-kafka-options
  {:crux.kafka.embedded/zookeeper-data-dir (str storage-dir "/zookeeper")
   :crux.kafka.embedded/kafka-log-dir (str storage-dir "/kafka-log")
   :crux.kafka.embedded/kafka-port 9092})

(def embedded-kafka (ek/start-embedded-kafka embedded-kafka-options))
;; end::ek-example[]

;; tag::ek-close[]
(.close embedded-kafka)
;; end::ek-close[]

;; tag::ln-example[]
(require '[crux.api :as api])

(def storage-dir "dev-storage")
(def local-node-options {:db-dir (str storage-dir "/data")
                         :bootstrap-servers "localhost:9092"})
(def local-node (api/start-local-node local-node-options))
;; end::ln-example[]

;; tag::ln-close[]
(.close local-node)
;; end::ln-close[]
