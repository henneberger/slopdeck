#!/bin/bash

# Variables
AWS_REGION="us-east-1"
INSTANCE_TYPE="c8g.2xlarge" # Minimum 2GB memory
KEY_NAME="newkey2"
SECURITY_GROUP="sg-0044649c05b1f6ce5"
AMI_ID="ami-055e62b4ea2fe95fd" # Amazon Linux 2 AMI (x86_64)
TAG="SimpleServiceDeploy"
SPOT_PRICE="0.05"
INSTANCE_ID=""
USER_DATA=$(base64 -i start.sh)

# Launch EC2 instance
SPOT_REQUEST_ID=$(aws ec2 request-spot-instances \
    --spot-price "$SPOT_PRICE" \
    --instance-count 1 \
    --type "one-time" \
    --launch-specification "{
        \"ImageId\": \"$AMI_ID\",
        \"InstanceType\": \"$INSTANCE_TYPE\",
        \"KeyName\": \"$KEY_NAME\",
        \"SecurityGroupIds\": [\"$SECURITY_GROUP\"],
        \"SubnetId\": \"subnet-0771be1e0f9e8e63d\",
        \"BlockDeviceMappings\": [
            {
                \"DeviceName\": \"/dev/xvda\",
                \"Ebs\": {\"VolumeSize\": 20, \"DeleteOnTermination\": true}
            }
        ],
        \"UserData\": \"$USER_DATA\"
    }" \
    --query 'SpotInstanceRequests[0].SpotInstanceRequestId' \
    --output text \
    --region $AWS_REGION)


echo "Spot request submitted with ID: $SPOT_REQUEST_ID"

while [ -z "$INSTANCE_ID" ] || [ "$INSTANCE_ID" == "None" ]; do
  echo "Waiting for spot instance to be provisioned..."
    sleep 5
    INSTANCE_ID=$(aws ec2 describe-spot-instance-requests \
        --spot-instance-request-ids $SPOT_REQUEST_ID \
        --query 'SpotInstanceRequests[0].InstanceId' \
        --output text \
        --region $AWS_REGION)
done
# Wait for the spot request to be fulfilled
echo "Waiting for spot instance to be provisioned..."
INSTANCE_ID=""
while [ -z "$INSTANCE_ID" ] || [ "$INSTANCE_ID" == "None" ]; do
    sleep 5
    INSTANCE_ID=$(aws ec2 describe-spot-instance-requests \
        --spot-instance-request-ids $SPOT_REQUEST_ID \
        --query 'SpotInstanceRequests[0].InstanceId' \
        --output text \
        --region $AWS_REGION)
done

echo "Spot instance provisioned with ID: $INSTANCE_ID"

echo "Waiting for instance to be running..."
aws ec2 wait instance-running --instance-ids $INSTANCE_ID --region $AWS_REGION

sleep 5

# Get Public DNS of the instance
PUBLIC_DNS=$(aws ec2 describe-instances \
    --instance-ids $INSTANCE_ID \
    --region $AWS_REGION \
    --query 'Reservations[0].Instances[0].PublicDnsName' \
    --output text)

echo "Instance is ready at $PUBLIC_DNS"

# Connect to the instance and set up environment
ssh -o StrictHostKeyChecking=no -i "~/$KEY_NAME.pem" ec2-user@$PUBLIC_DNS <<EOF
# Update and install dependencies
sudo yum update -y
sudo yum install -y java python3.11 python3.11-pip git
sudo pip3.11 install torch Pillow torchvision transformers flask
mkdir -p ~/services/java
mkdir -p ~/services/python

EOF

# Copy service files to the instance
scp -i "~/$KEY_NAME.pem" \
    target/federate-1.0-SNAPSHOT.jar \
    ec2-user@$PUBLIC_DNS:~/services/java/

scp -i "~/$KEY_NAME.pem" \
    python/app.py \
    ec2-user@$PUBLIC_DNS:~/services/python/

scp -i "~/$KEY_NAME.pem" \
    python/text.py \
    ec2-user@$PUBLIC_DNS:~/services/python/

scp -i "~/$KEY_NAME.pem" \
    start.sh \
    ec2-user@$PUBLIC_DNS:~

# Start services on the instance
ssh -i "~/$KEY_NAME.pem" ec2-user@$PUBLIC_DNS <<EOF
chmod +x ~/start.sh
~/start.sh
EOF


echo "$PUBLIC_DNS"
