#!/bin/bash

rm -rf 1-docker-data/zookeeper/volumes/data/*
rm -rf 1-docker-data/zookeeper/volumes/log/*
rm -rf 1-docker-data/kafka/volumes/data/*
rm -rf 1-docker-data/kafka/volumes/logs/*
docker volume prune
