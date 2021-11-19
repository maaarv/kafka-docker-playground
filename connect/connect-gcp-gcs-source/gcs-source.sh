#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"
source ${DIR}/../../scripts/utils.sh

PROJECT=${1:-vincent-de-saboulin-lab}

KEYFILE="${DIR}/keyfile.json"
if [ ! -f ${KEYFILE} ]
then
     logerror "ERROR: the file ${KEYFILE} file is not present!"
     exit 1
fi

${DIR}/../../environment/plaintext/start.sh "${PWD}/docker-compose.plaintext.yml"

GCS_BUCKET_NAME=kafka-docker-playground-bucket-${USER}${TAG}
GCS_BUCKET_NAME=${GCS_BUCKET_NAME//[-.]/}

log "Doing gsutil authentication"
set +e
docker rm -f gcloud-config
set -e
docker run -i -v ${KEYFILE}:/tmp/keyfile.json --name gcloud-config google/cloud-sdk:latest gcloud auth activate-service-account --project ${PROJECT} --key-file /tmp/keyfile.json

log "Creating bucket name <$GCS_BUCKET_NAME>, if required"
set +e
docker run -i --volumes-from gcloud-config google/cloud-sdk:latest gsutil mb -p $(cat ${KEYFILE} | jq -r .project_id) gs://$GCS_BUCKET_NAME
set -e

log "Removing existing objects in GCS, if applicable"
set +e
docker run -i --volumes-from gcloud-config google/cloud-sdk:latest gsutil rm -r gs://$GCS_BUCKET_NAME/topics/gcs_topic
set -e


##########################
## SINK
##########################

log "Sending messages to topic gcs_topic"
seq -f "{\"f1\": \"value%g\"}" 10 | docker exec -i connect kafka-avro-console-producer --broker-list broker:9092 --property schema.registry.url=http://schema-registry:8081 --topic gcs_topic --property value.schema='{"type":"record","name":"myrecord","fields":[{"name":"f1","type":"string"}]}'


log "Creating GCS Sink connector"
curl -X PUT \
     -H "Content-Type: application/json" \
     --data '{
               "connector.class": "io.confluent.connect.gcs.GcsSinkConnector",
                    "tasks.max" : "1",
                    "topics" : "gcs_topic",
                    "gcs.bucket.name" : "'"$GCS_BUCKET_NAME"'",
                    "gcs.part.size": "5242880",
                    "flush.size": "3",
                    "gcs.credentials.path": "/tmp/keyfile.json",
                    "storage.class": "io.confluent.connect.gcs.storage.GcsStorage",
                    "format.class": "io.confluent.connect.gcs.format.avro.AvroFormat",
                    "partitioner.class": "io.confluent.connect.storage.partitioner.DefaultPartitioner",
                    "schema.compatibility": "NONE",
                    "confluent.topic.bootstrap.servers": "broker:9092",
                    "confluent.topic.replication.factor": "1"
          }' \
     http://localhost:8083/connectors/GCSSinkConnector/config | jq .

sleep 10

log "Listing objects of in GCS"
docker run -i --volumes-from gcloud-config google/cloud-sdk:latest gsutil ls gs://$GCS_BUCKET_NAME/topics/gcs_topic/partition=0/

log "Getting one of the avro files locally and displaying content with avro-tools"
docker run -i --volumes-from gcloud-config -v /tmp:/tmp/ google/cloud-sdk:latest gsutil cp gs://$GCS_BUCKET_NAME/topics/gcs_topic/partition=0/gcs_topic+0+0000000000.avro /tmp/gcs_topic+0+0000000000.avro

docker run --rm -v /tmp:/tmp actions/avro-tools tojson /tmp/gcs_topic+0+0000000000.avro

docker rm -f gcloud-config

##########################
## SOURCE
##########################
log "Creating GCS Source connector"
curl -X PUT \
     -H "Content-Type: application/json" \
     --data '{
               "connector.class": "io.confluent.connect.gcs.GcsSourceConnector",
                    "gcs.bucket.name" : "'"$GCS_BUCKET_NAME"'",
                    "gcs.credentials.path" : "/tmp/keyfile.json",
                    "format.class": "io.confluent.connect.gcs.format.avro.AvroFormat",
                    "tasks.max" : "1",
                    "confluent.topic.bootstrap.servers" : "broker:9092",
                    "confluent.topic.replication.factor" : "1",
                    "transforms" : "AddPrefix",
                    "transforms.AddPrefix.type" : "org.apache.kafka.connect.transforms.RegexRouter",
                    "transforms.AddPrefix.regex" : ".*",
                    "transforms.AddPrefix.replacement" : "copy_of_$0"
          }' \
     http://localhost:8083/connectors/GCSSourceConnector/config | jq .

sleep 10

log "Verify messages are in topic copy_of_gcs_topic"
timeout 60 docker exec connect kafka-avro-console-consumer -bootstrap-server broker:9092 --property schema.registry.url=http://schema-registry:8081 --topic copy_of_gcs_topic --from-beginning --max-messages 9
