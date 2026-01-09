/**
 * Admin Sidebar State Management
 * 
 * Handles:
 * - Collapse/expand toggle
 * - Pin state persistence
 * - Mobile drawer open/close
 * - LocalStorage persistence
 * - Keyboard shortcuts (optional)
 */

(function() {
  'use strict';

  // State keys for localStorage
  const STORAGE_KEY_STATE = 'boundary-admin-sidebar-state';
  const STORAGE_KEY_PINNED = 'boundary-admin-sidebar-pinned';

  // Get the admin shell container
  const adminShell = document.querySelector('.admin-shell');
  if (!adminShell) return; // Not on admin page

  // Get control buttons
  const toggleButton = document.querySelector('.sidebar-toggle');
  const pinButton = document.querySelector('.sidebar-pin');
  const mobileToggle = document.querySelector('.mobile-menu-toggle');
  const overlay = document.querySelector('.admin-overlay');

  // Initialize state from localStorage or defaults
  function getInitialState() {
    const savedState = localStorage.getItem(STORAGE_KEY_STATE);
    const savedPinned = localStorage.getItem(STORAGE_KEY_PINNED);
    
    return {
      state: savedState || 'expanded', // 'expanded' or 'collapsed'
      pinned: savedPinned === 'true',
      mobileOpen: false
    };
  }

  let sidebarState = getInitialState();

  // Apply state to DOM
  function applyState() {
    adminShell.setAttribute('data-sidebar-state', sidebarState.state);
    adminShell.setAttribute('data-sidebar-pinned', String(sidebarState.pinned));
    adminShell.setAttribute('data-sidebar-open', String(sidebarState.mobileOpen));

    // Persist to localStorage (not mobile open state)
    localStorage.setItem(STORAGE_KEY_STATE, sidebarState.state);
    localStorage.setItem(STORAGE_KEY_PINNED, String(sidebarState.pinned));

    // Update button icons/aria labels
    updateButtonStates();
  }

  // Update button states for accessibility
  function updateButtonStates() {
    if (toggleButton) {
      const isCollapsed = sidebarState.state === 'collapsed';
      toggleButton.setAttribute('aria-label', isCollapsed ? 'Expand sidebar' : 'Collapse sidebar');
      toggleButton.setAttribute('aria-expanded', String(!isCollapsed));
    }

    if (pinButton) {
      pinButton.setAttribute('aria-label', sidebarState.pinned ? 'Unpin sidebar' : 'Pin sidebar');
      pinButton.setAttribute('aria-pressed', String(sidebarState.pinned));
    }

    if (mobileToggle) {
      mobileToggle.setAttribute('aria-label', sidebarState.mobileOpen ? 'Close menu' : 'Open menu');
      mobileToggle.setAttribute('aria-expanded', String(sidebarState.mobileOpen));
    }
  }

  // Toggle sidebar collapsed/expanded
  function toggleSidebar() {
    sidebarState.state = sidebarState.state === 'expanded' ? 'collapsed' : 'expanded';
    applyState();
  }

  // Toggle pin state
  function togglePin() {
    sidebarState.pinned = !sidebarState.pinned;
    applyState();
  }

  // Toggle mobile drawer
  function toggleMobileDrawer() {
    sidebarState.mobileOpen = !sidebarState.mobileOpen;
    applyState();
  }

  // Close mobile drawer (when clicking overlay or nav link)
  function closeMobileDrawer() {
    if (sidebarState.mobileOpen) {
      sidebarState.mobileOpen = false;
      applyState();
    }
  }

  // Auto-collapse on desktop when unpinned and mouse leaves
  function handleMouseLeave() {
    // Only on desktop (> 768px)
    if (window.innerWidth <= 768) return;
    
    // Only if not pinned
    if (sidebarState.pinned) return;

    // Collapse after brief delay
    setTimeout(() => {
      if (sidebarState.state === 'expanded') {
        sidebarState.state = 'collapsed';
        applyState();
      }
    }, 300);
  }

  // Auto-expand on desktop when mouse enters (if unpinned)
  function handleMouseEnter() {
    // Only on desktop (> 768px)
    if (window.innerWidth <= 768) return;
    
    // Only if not pinned
    if (sidebarState.pinned) return;

    if (sidebarState.state === 'collapsed') {
      sidebarState.state = 'expanded';
      applyState();
    }
  }

  // Event Listeners
  if (toggleButton) {
    toggleButton.addEventListener('click', toggleSidebar);
  }

  if (pinButton) {
    pinButton.addEventListener('click', togglePin);
  }

  if (mobileToggle) {
    mobileToggle.addEventListener('click', toggleMobileDrawer);
  }

  if (overlay) {
    overlay.addEventListener('click', closeMobileDrawer);
  }

  // Close mobile drawer when clicking nav links
  const navLinks = document.querySelectorAll('.entity-list a, .admin-sidebar-footer a');
  navLinks.forEach(link => {
    link.addEventListener('click', () => {
      if (window.innerWidth <= 768) {
        closeMobileDrawer();
      }
    });
  });

  // Auto collapse/expand on hover (desktop only, when not pinned)
  const sidebar = document.querySelector('.admin-sidebar');
  if (sidebar) {
    sidebar.addEventListener('mouseenter', handleMouseEnter);
    sidebar.addEventListener('mouseleave', handleMouseLeave);
  }

  // Keyboard shortcuts (optional enhancement)
  document.addEventListener('keydown', (e) => {
    // Ctrl/Cmd + B = Toggle sidebar
    if ((e.ctrlKey || e.metaKey) && e.key === 'b') {
      e.preventDefault();
      toggleSidebar();
    }

    // Escape = Close mobile drawer
    if (e.key === 'Escape' && sidebarState.mobileOpen) {
      closeMobileDrawer();
    }
  });

  // Handle window resize
  let resizeTimeout;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimeout);
    resizeTimeout = setTimeout(() => {
      // Close mobile drawer if resizing to desktop
      if (window.innerWidth > 768 && sidebarState.mobileOpen) {
        closeMobileDrawer();
      }
    }, 150);
  });

  // Initialize on page load
  applyState();

  // Add data-label attributes to nav links for tooltips
  document.querySelectorAll('.entity-list a').forEach(link => {
    const text = link.textContent.trim();
    if (text && !link.hasAttribute('data-label')) {
      link.setAttribute('data-label', text);
    }
  });

})();
