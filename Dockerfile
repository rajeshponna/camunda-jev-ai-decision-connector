# Adds this connector to Camunda's official connectors runtime.
# Build first: mvn clean package
# Match the tag to your Camunda version.
FROM camunda/connectors-bundle:8.9.0
COPY target/jev-ai-decision-connector-1.2.0.jar /opt/app/
