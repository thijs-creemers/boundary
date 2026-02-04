(ns boundary.realtime.ports
  "Port definitions for real-time WebSocket communication.
  
   This module defines protocols for WebSocket-based messaging, similar to
   Phoenix Channels (Elixir) or Socket.io (Node.js). Supports point-to-point,
   broadcast, and role-based messaging with JWT authentication.
   
   Key Features:
   - WebSocket connection management
   - Message routing (user, role, broadcast, connection-specific)
   - JWT-based authentication
   - Connection registry (in-memory or Redis)")

;; =============================================================================
;; Real-time Service Ports
;; =============================================================================

(defprotocol IRealtimeService
  "Service orchestration for WebSocket real-time communication.
  
  Shell layer implementation coordinates between core logic (pure functions)
  and adapters (WebSocket I/O, connection registry). Implements the imperative
  shell in the FC/IS architecture pattern."

  (connect [this ws-connection query-params]
    "Establish WebSocket connection with JWT authentication.
    
    Validates JWT token from query parameters, creates connection record,
    and registers connection in registry. Token extraction and validation
    delegated to auth module (boundary/user).
    
    Args:
      ws-connection - WebSocket connection adapter (IWebSocketConnection)
      query-params - Map of query parameters (expects 'token' key)
    
    Returns:
      Connection ID (UUID) if successful
    
    Throws:
      ExceptionInfo with {:type :unauthorized :message ...} if:
        - Token missing from query params
        - Token invalid (expired, malformed, wrong signature)
        - JWT verification fails")

  (disconnect [this connection-id]
    "Close WebSocket connection and clean up.
    
    Removes connection from registry, closes WebSocket, and logs disconnection.
    Idempotent - safe to call multiple times for same connection.
    
    Args:
      connection-id - UUID of connection to close
    
    Returns:
      nil
    
    Side Effects:
      - Removes connection from registry
      - Closes WebSocket connection
      - Logs disconnection event")

  (send-to-user [this user-id message]
    "Send message to all WebSocket connections for a user.
    
    Routes message to all active connections belonging to the specified user.
    User may have multiple connections (e.g., multiple browser tabs, mobile app).
    Uses core/message routing logic to determine target connections.
    
    Args:
      user-id - Target user UUID
      message - Message map with:
                :type - Message type keyword (e.g., :notification, :update)
                :payload - Message data (will be JSON-encoded)
                :timestamp - Optional timestamp (auto-added if missing)
    
    Returns:
      Number of connections message was sent to (integer >= 0)
    
    Side Effects:
      - Sends message over WebSocket to matching connections
      - Logs send event (debug level)")

  (send-to-role [this role message]
    "Send message to all WebSocket connections with a specific role.
    
    Routes message to all connections where user has the specified role.
    Useful for admin notifications, moderator alerts, etc.
    Uses core/message routing logic with role-based filtering.
    
    Args:
      role - Target role keyword (e.g., :admin, :moderator)
      message - Message map (same structure as send-to-user)
    
    Returns:
      Number of connections message was sent to (integer >= 0)
    
    Side Effects:
      - Sends message over WebSocket to matching connections
      - Logs send event (debug level)")

  (broadcast [this message]
    "Send message to all active WebSocket connections.
    
    Routes message to every connection in the registry. Use sparingly -
    consider send-to-role or send-to-user for more targeted messaging.
    
    Args:
      message - Message map (same structure as send-to-user)
    
    Returns:
      Number of connections message was sent to (integer >= 0)
    
    Side Effects:
      - Sends message over WebSocket to all connections
      - Logs broadcast event (info level)")

  (send-to-connection [this connection-id message]
    "Send message to specific WebSocket connection.
    
    Direct send to individual connection by ID. Useful for connection-specific
    messages like job progress updates (tied to connection, not user).
    
    Args:
      connection-id - Target connection UUID
      message - Message map (same structure as send-to-user)
    
    Returns:
      true if sent, false if connection not found
    
    Side Effects:
      - Sends message over WebSocket if connection exists
      - Logs send event (debug level)"))

;; =============================================================================
;; Connection Registry Ports
;; =============================================================================

(defprotocol IConnectionRegistry
  "Storage for active WebSocket connections.
  
  Implemented by in-memory registry (atom) or Redis-backed registry for
  multi-server scaling. Stores mapping of connection-id -> (connection, ws-adapter).
  
  Registry Structure:
    {connection-id {:connection <Connection record from core>
                    :ws-adapter <IWebSocketConnection adapter>}}"

  (register [this connection-id connection ws-connection]
    "Register new WebSocket connection in registry.
    
    Stores connection record and WebSocket adapter for later message routing.
    Connection record is pure data (from core layer), ws-adapter handles I/O.
    
    Args:
      connection-id - UUID for connection (from Connection record)
      connection - Connection record from core/connection.clj with:
                   :id - Connection UUID (same as connection-id)
                   :user-id - User UUID
                   :roles - Set of role keywords
                   :metadata - Optional metadata map
                   :created-at - Instant
      ws-connection - WebSocket adapter (IWebSocketConnection)
    
    Returns:
      nil
    
    Side Effects:
      - Adds entry to registry (atom or Redis)
      - May evict old connections if max-connections limit reached")

  (unregister [this connection-id]
    "Remove connection from registry.
    
    Called when WebSocket closes (client disconnect, timeout, error).
    Idempotent - safe to call multiple times.
    
    Args:
      connection-id - UUID of connection to remove
    
    Returns:
      nil
    
    Side Effects:
      - Removes entry from registry
      - Does NOT close WebSocket (caller's responsibility)")

  (find-by-user [this user-id]
    "Find all WebSocket adapters for a user.
    
    Returns WebSocket adapters (not Connection records) for immediate sending.
    User may have multiple active connections (tabs, devices).
    
    Args:
      user-id - User UUID
    
    Returns:
      Vector of IWebSocketConnection adapters (may be empty)")

  (find-by-role [this role]
    "Find all WebSocket adapters with a specific role.
    
    Returns adapters for connections where user has the specified role.
    Uses Connection record's :roles set for filtering.
    
    Args:
      role - Role keyword (e.g., :admin)
    
    Returns:
      Vector of IWebSocketConnection adapters (may be empty)")

  (all-connections [this]
    "Get all active WebSocket adapters.
    
    Used for broadcast messages. Returns adapters (not Connection records)
    for immediate sending.
    
    Returns:
      Vector of IWebSocketConnection adapters (may be empty)")

  (connection-count [this]
    "Get number of active connections.
    
    Used for metrics and monitoring.
    
    Returns:
      Integer count (>= 0)")

  (find-connection [this connection-id]
    "Find Connection record by ID.
    
    Returns the pure Connection record (not ws-adapter) for inspection.
    Used for debugging and monitoring.
    
    Args:
      connection-id - Connection UUID
    
    Returns:
      Connection record or nil if not found"))

;; =============================================================================
;; WebSocket Connection Ports
;; =============================================================================

(defprotocol IWebSocketConnection
  "WebSocket connection adapter for sending messages.
  
  Wraps actual WebSocket implementation (Ring/Reitit/http-kit). Adapters
  translate generic message maps to WebSocket frames (JSON encoding).
  
  Implemented by:
    - RingWebSocketAdapter (Ring 2.0 WebSocket)
    - HttpKitWebSocketAdapter (http-kit WebSocket)
    - TestWebSocketAdapter (in-memory for testing)"

  (send-message [this message]
    "Send message over WebSocket connection.
    
    Encodes message map to JSON and sends as WebSocket text frame.
    Non-blocking - returns immediately, actual send is asynchronous.
    
    Args:
      message - Message map with:
                :type - Message type keyword
                :payload - Message data (must be JSON-serializable)
                :timestamp - Instant (auto-added if missing)
    
    Returns:
      nil
    
    Side Effects:
      - Sends JSON-encoded message over WebSocket
      - May fail silently if connection closed (logged by adapter)
    
    Throws:
      Exception if JSON encoding fails (invalid payload)")

  (close [this]
    "Close WebSocket connection.
    
    Graceful close - sends close frame to client and releases resources.
    Idempotent - safe to call multiple times.
    
    Returns:
      nil
    
    Side Effects:
      - Sends WebSocket close frame
      - Releases connection resources")

  (connection-id [this]
    "Get connection ID for this WebSocket.
    
    Returns the UUID that ties this adapter to its Connection record
    in the registry.
    
    Returns:
      Connection UUID")

  (open? [this]
    "Check if WebSocket connection is open.
    
    Used to avoid sending to closed connections.
    
    Returns:
      Boolean - true if connection is open and ready"))

;; =============================================================================
;; JWT Verification Port
;; =============================================================================

(defprotocol IJWTVerifier
  "JWT token verification.
  
  Delegates to boundary/user module for actual verification. This port
  exists to avoid direct dependency on user module from core layer.
  
  Implemented by:
    - UserJWTAdapter (delegates to boundary.user.shell.jwt)
    - TestJWTAdapter (returns mock claims for testing)"

  (verify-jwt [this token]
    "Verify JWT token and extract claims.
    
    Validates token signature, expiry, and structure. Returns claims
    map for creating Connection record.
    
    Args:
      token - JWT token string
    
    Returns:
      Claims map with:
        :user-id - User UUID
        :email - User email
        :roles - Set of role keywords
        :permissions - Set of permission keywords
        :exp - Expiry timestamp (seconds since epoch)
        :iat - Issued-at timestamp
    
    Throws:
      ExceptionInfo with {:type :unauthorized :message ...} if:
        - Token expired
        - Invalid signature
        - Malformed token
        - Missing required claims"))
