#!/usr/bin/env zsh

# Script to create 10 random users and retrieve them using the Boundary API
# This assumes the HTTP server is already running

set -e

# Configuration
API_BASE_URL="${API_BASE_URL:-http://localhost:3000}"

echo "========================================="
echo "Creating 10 Random Users"
echo "========================================="
echo "API Base URL: $API_BASE_URL"
echo ""

# Array of random names for variety
FIRST_NAMES=("Thijs" "Armando" "Alice" "Bob" "Charlie" "Diana" "Eve" "Frank" "Grace" "Henry" "Ivy" "Jack")
LAST_NAMES=("Smith" "Johnson" "Williams" "Brown" "Jones" "Garcia" "Miller" "Davis" "Rodriguez" "Martinez")

# Create 10 users
echo "Creating users..."
for i in {1..10}; do
  FIRST=${FIRST_NAMES[$((i-1))]}
  LAST=${LAST_NAMES[$((i-1))]}
  EMAIL=$(echo "${FIRST}.${LAST}@example.com" | tr '[:upper:]' '[:lower:]')
  
  echo "  [$i/10] Creating ${FIRST} ${LAST} (${EMAIL})..."
  
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${API_BASE_URL}/api/users" \
    -H "Content-Type: application/json" \
    -d "{
      \"email\": \"${EMAIL}\",
      \"name\": \"${FIRST} ${LAST}\",
      \"password\": \"password${i}\",
      \"role\": \"user\",
      \"active\": true
    }")
  
  HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
  BODY=$(echo "$RESPONSE" | sed '$d')
  
  if [ "$HTTP_CODE" = "201" ]; then
    echo "    ✓ Created successfully"
  else
    echo "    ✗ Failed (HTTP $HTTP_CODE)"
    echo "    Response: $BODY"
  fi
done

echo ""
echo "========================================="
echo "Retrieving Created Users"
echo "========================================="

RESPONSE=$(curl -s -w "\n%{http_code}" -X GET \
  "${API_BASE_URL}/api/users&limit=20")

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
  echo "✓ Successfully retrieved users"
  echo ""
  echo "$BODY" | jq '.'
else
  echo "✗ Failed to retrieve users (HTTP $HTTP_CODE)"
  echo "Response: $BODY"
fi
echo ""
echo "========================================="
echo "Summary"
echo "========================================="
echo "You can retrieve these users again with:"
echo "  curl -s '${API_BASE_URL}/api/users' | jq '.'"
echo "========================================="
