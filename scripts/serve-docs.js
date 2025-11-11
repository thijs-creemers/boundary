#!/usr/bin/env node

/**
 * Smart documentation server with port conflict resolution
 * Integrates with Boundary Framework's port management philosophy
 */

const http = require('http');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

// Configuration
const DEFAULT_PORT = 8080;
const PORT_RANGE_SIZE = 20; // Try ports 8080-8099
const DOCS_DIR = path.join(__dirname, '..', 'resources', 'public', 'docs');

/**
 * Check if a port is available
 */
function isPortAvailable(port) {
  return new Promise((resolve) => {
    const server = http.createServer();
    
    server.listen(port, () => {
      server.close(() => resolve(true));
    });
    
    server.on('error', () => resolve(false));
  });
}

/**
 * Find an available port in the range
 */
async function findAvailablePort(startPort = DEFAULT_PORT) {
  for (let port = startPort; port < startPort + PORT_RANGE_SIZE; port++) {
    if (await isPortAvailable(port)) {
      return port;
    }
  }
  throw new Error(`No available ports in range ${startPort}-${startPort + PORT_RANGE_SIZE - 1}`);
}

/**
 * Check if docs directory exists and has content
 */
function validateDocsDirectory() {
  if (!fs.existsSync(DOCS_DIR)) {
    console.error('❌ Documentation directory not found:', DOCS_DIR);
    console.log('💡 Run "npm run build-docs" first to generate documentation');
    process.exit(1);
  }
  
  const files = fs.readdirSync(DOCS_DIR);
  if (files.length === 0) {
    console.error('❌ Documentation directory is empty:', DOCS_DIR);
    console.log('💡 Run "npm run build-docs" first to generate documentation');
    process.exit(1);
  }
}

/**
 * Get environment-specific configuration
 */
function getEnvironmentConfig() {
  // Check for Docker environment
  const isDocker = fs.existsSync('/.dockerenv') || 
                   process.env.DOCKER_CONTAINER === 'true';
  
  // Check for development indicators
  const isDevelopment = process.env.NODE_ENV === 'development' ||
                       fs.existsSync('deps.edn') ||
                       process.cwd().includes('/dev/') ||
                       process.argv.includes('--dev');

  return {
    isDocker,
    isDevelopment,
    strategy: isDocker ? 'exact-or-fail' : 'range-search'
  };
}

/**
 * Start the documentation server
 */
async function startServer() {
  console.log('📚 Starting Boundary Framework Documentation Server...');
  
  // Validate environment
  validateDocsDirectory();
  const envConfig = getEnvironmentConfig();
  
  console.log(`🔍 Environment: ${envConfig.isDocker ? 'Docker' : 'Local'} (${envConfig.isDevelopment ? 'Development' : 'Production-like'})`);
  console.log(`🎯 Port strategy: ${envConfig.strategy}`);
  
  let port;
  
  try {
    if (envConfig.strategy === 'exact-or-fail') {
      // Docker or production-like: use exact port or fail
      if (await isPortAvailable(DEFAULT_PORT)) {
        port = DEFAULT_PORT;
        console.log(`✅ Using exact port: ${port}`);
      } else {
        throw new Error(`Port ${DEFAULT_PORT} is not available in ${envConfig.isDocker ? 'Docker' : 'production-like'} environment`);
      }
    } else {
      // Development: find available port in range
      port = await findAvailablePort(DEFAULT_PORT);
      if (port !== DEFAULT_PORT) {
        console.log(`⚡ Port conflict resolved: using ${port} instead of ${DEFAULT_PORT}`);
      } else {
        console.log(`✅ Using preferred port: ${port}`);
      }
    }
  } catch (error) {
    console.error('❌', error.message);
    console.log('💡 To resolve:');
    console.log('   - Stop process using the port');
    console.log('   - Use a different port with: npm run serve-docs -- --port 8081');
    process.exit(1);
  }
  
  // Check for custom port argument
  const portArg = process.argv.find(arg => arg.startsWith('--port='));
  if (portArg) {
    const customPort = parseInt(portArg.split('=')[1]);
    if (await isPortAvailable(customPort)) {
      port = customPort;
      console.log(`🎛️  Using custom port: ${port}`);
    } else {
      console.error(`❌ Custom port ${customPort} is not available`);
      process.exit(1);
    }
  }
  
  // Start http-server with the allocated port
  const serverArgs = [
    'http-server',
    DOCS_DIR,
    '-p', port.toString(),
    '-o',
    '--cors',
    '-c-1' // Disable caching for development
  ];
  
  console.log(`🚀 Starting server: npx ${serverArgs.join(' ')}`);
  console.log(`📖 Documentation will be available at: http://localhost:${port}`);
  
  const server = spawn('npx', serverArgs, {
    stdio: 'inherit',
    cwd: process.cwd()
  });
  
  server.on('error', (error) => {
    console.error('❌ Failed to start server:', error.message);
    process.exit(1);
  });
  
  server.on('close', (code) => {
    if (code !== 0) {
      console.error(`❌ Server exited with code ${code}`);
      process.exit(code);
    }
  });
  
  // Handle graceful shutdown
  process.on('SIGINT', () => {
    console.log('\n👋 Shutting down documentation server...');
    server.kill('SIGINT');
  });
}

// Show help if requested
if (process.argv.includes('--help') || process.argv.includes('-h')) {
  console.log(`
📚 Boundary Framework Documentation Server

Usage:
  npm run serve-docs          Start with automatic port allocation
  npm run serve-docs -- --port=8081    Use specific port
  npm run serve-docs -- --help         Show this help

Features:
  ✅ Automatic port conflict resolution
  ✅ Environment detection (Docker/Local/Development)
  ✅ Smart port allocation strategy
  ✅ Documentation validation
  ✅ Graceful error handling

Port Strategy:
  🐳 Docker: Exact port (${DEFAULT_PORT}) or fail
  🔧 Development: Search range ${DEFAULT_PORT}-${DEFAULT_PORT + PORT_RANGE_SIZE - 1}
  🏭 Production-like: Exact port (${DEFAULT_PORT}) or fail
`);
  process.exit(0);
}

// Start the server
startServer().catch(error => {
  console.error('❌ Unexpected error:', error);
  process.exit(1);
});