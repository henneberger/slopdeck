#!/bin/bash

cd /home/ec2-user/services/python
# Start Python service
nohup gunicorn -w 2 -b 0.0.0.0:6000 app:app --log-level debug > ~/python_service.log 2>&1 &

# Start Python service
nohup gunicorn -w 1 -b 0.0.0.0:6001 text:app --log-level debug > ~/python_service_text.log 2>&1 &

sleep 10

# Start Java service
nohup java -cp ~/services/java/federate-1.0-SNAPSHOT.jar com.henneberger.SimpleWebServer  > ~/java_service.log 2>&1 &


echo "Both services have been started."