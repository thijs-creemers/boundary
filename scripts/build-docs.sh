#!/bin/bash

# Build Boundary Framework documentation using Antora

set -e

echo "🏗️  Building Boundary Framework documentation..."

# Check if Node.js is available
if ! command -v node &> /dev/null; then
    echo "❌ Node.js is required but not installed. Please install Node.js first."
    exit 1
fi

# Check if npm is available
if ! command -v npm &> /dev/null; then
    echo "❌ npm is required but not installed. Please install npm first."
    exit 1
fi

# Install dependencies if node_modules doesn't exist
if [ ! -d "node_modules" ]; then
    echo "📦 Installing dependencies..."
    npm install
fi

# Create output directory if it doesn't exist
mkdir -p resources/public

# Build the documentation site
echo "📚 Generating documentation site..."
npx antora --fetch antora-playbook.yml

echo "✅ Documentation built successfully!"
echo "📂 Site generated in: resources/public/docs"
echo ""
echo "To serve the documentation locally:"
echo "  npm run serve-docs          # Smart server with port conflict resolution"
echo "  npm run serve-docs-simple   # Basic server (port 8080 only)"
echo ""
echo "Alternative manual methods:"
echo "  cd resources/public/docs && python3 -m http.server 8080"
echo "  cd resources/public/docs && npx http-server -p 8080 -o"
