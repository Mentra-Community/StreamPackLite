#!/bin/bash

ffplay -fflags nobuffer -flags low_delay -framedrop -sync ext -listen 1 -i rtmp://0.0.0.0:1935/s/streamKey
