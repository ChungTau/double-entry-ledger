#!/bin/bash
set -e

KIBANA_URL="${KIBANA_URL:-http://kibana:5601}"
MAX_RETRIES="${MAX_RETRIES:-30}"
RETRY_INTERVAL="${RETRY_INTERVAL:-10}"

echo "Waiting for Kibana to be ready at ${KIBANA_URL}..."

# Retry loop for Kibana readiness
for i in $(seq 1 $MAX_RETRIES); do
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${KIBANA_URL}/api/status" || echo "000")
    if [ "$HTTP_STATUS" = "200" ]; then
        echo "Kibana is ready!"
        break
    fi
    echo "Attempt $i/$MAX_RETRIES: Kibana not ready (HTTP $HTTP_STATUS). Retrying in ${RETRY_INTERVAL}s..."
    sleep $RETRY_INTERVAL
done

if [ "$HTTP_STATUS" != "200" ]; then
    echo "ERROR: Kibana did not become ready after $MAX_RETRIES attempts"
    exit 1
fi

# Additional wait for full initialization
echo "Waiting additional 10s for Kibana to fully initialize..."
sleep 10

# Create data view with idempotency handling
echo "Creating 'transactions' data view..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${KIBANA_URL}/api/data_views/data_view" \
    -H "kbn-xsrf: true" \
    -H "Content-Type: application/json" \
    -d '{
        "data_view": {
            "id": "transactions",
            "title": "transactions",
            "name": "Transactions",
            "timeFieldName": "bookedAt",
            "allowNoIndex": true
        }
    }')

if [ "$RESPONSE" -eq 200 ] || [ "$RESPONSE" -eq 201 ]; then
    echo "Data View created successfully."
elif [ "$RESPONSE" -eq 409 ]; then
    echo "Data View already exists (Skipping)."
else
    echo "Failed to create Data View. HTTP Code: $RESPONSE"
    exit 1
fi

echo "Kibana setup complete!"
