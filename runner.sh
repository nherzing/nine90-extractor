#!/bin/bash

quit=0
trap 'quit=1' SIGINT

echo "Starting server"
lein trampoline run -m nine90-extractor.server $1 &
server_pid=$!

for ((n=0;n<$3;n++))
do
    lein trampoline run -m nine90-extractor.client $2 &
    pids[$n]=$!
done

while (( quit != 1 ))
do
    sleep 1
done

kill -9 $server_pid
for pid in "${pids[@]}"
do
    kill -9 "$pid"
done
