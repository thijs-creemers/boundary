/**
 * Stitch Admin - Keyboard Shortcuts
 *
 * Global shortcuts:
 *   g h  - Go to Dashboard (/web/dashboard)
 *   g u  - Go to Users (/web/admin/user)
 *
 * Context shortcuts:
 *   n    - New/Create (in list view)
 *   e    - Edit (in detail view)
 *   /    - Focus search
 *   Esc  - Close modal/cancel edit
 */

(function() {
  'use strict';

  // Shortcut state
  let pendingPrefix = null;
  let prefixTimeout = null;

  // Configuration
  const SHORTCUTS = {
    // Go-to shortcuts (two-key combos: g + key)
    goto: {
      'h': '/web/dashboard',       // g h -> Dashboard
      'u': '/web/admin/user'       // g u -> Users (entity name is singular)
    },

    // Single-key shortcuts
    single: {
      'n': handleNew,
      'e': handleEdit,
      '/': handleSearch,
      'Escape': handleEscape
    }
  };

  /**
   * Check if focus is in an input element
   */
  function isInputFocused() {
    const active = document.activeElement;
    if (!active) return false;

    const tagName = active.tagName.toLowerCase();
    if (tagName === 'input' || tagName === 'textarea' || tagName === 'select') {
      return true;
    }

    // Also check contenteditable
    return active.isContentEditable;
  }

  /**
   * Navigate to URL
   */
  function navigateTo(url) {
    if (url && window.location.pathname !== url) {
      window.location.href = url;
    }
  }

  /**
   * Handle 'n' - New/Create
   * Clicks the first "Create" or "New" button found
   */
  function handleNew() {
    // Look for create button in toolbar
    const createBtn = document.querySelector(
      '.page-header-compact .button.primary, ' +
      '.toolbar .button.primary, ' +
      'a[href*="/new"], ' +
      'button[data-action="create"]'
    );

    if (createBtn) {
      createBtn.click();
      return true;
    }
    return false;
  }

  /**
   * Handle 'e' - Edit
   * Clicks the Edit button in detail view
   */
  function handleEdit() {
    const editBtn = document.querySelector(
      '.detail-actions .button[data-action="edit"], ' +
      'a[href*="/edit"], ' +
      'button[data-action="edit"]'
    );

    if (editBtn) {
      editBtn.click();
      return true;
    }
    return false;
  }

  /**
   * Handle '/' - Focus search
   */
  function handleSearch() {
    const searchInput = document.querySelector(
      'input[type="search"], ' +
      'input[name="search"], ' +
      'input[name="q"], ' +
      '.search-input, ' +
      '#search'
    );

    if (searchInput) {
      searchInput.focus();
      searchInput.select();
      return true;
    }
    return false;
  }

  /**
   * Handle Escape - Close modal or cancel
   */
  function handleEscape() {
    // Close any open modals
    const modal = document.querySelector('.modal[open], .modal.active, .modal-backdrop');
    if (modal) {
      // Try clicking close button first
      const closeBtn = modal.querySelector('.modal-close, [data-action="close"], button[type="button"]');
      if (closeBtn) {
        closeBtn.click();
        return true;
      }
      // Or trigger close event
      modal.dispatchEvent(new CustomEvent('close'));
      return true;
    }

    // Cancel inline edit
    const cancelBtn = document.querySelector('.inline-cancel');
    if (cancelBtn) {
      cancelBtn.click();
      return true;
    }

    // Close mobile sidebar
    const shell = document.querySelector('.admin-shell[data-sidebar-open="true"]');
    if (shell) {
      shell.setAttribute('data-sidebar-open', 'false');
      return true;
    }

    return false;
  }

  /**
   * Clear pending prefix state
   */
  function clearPrefix() {
    pendingPrefix = null;
    if (prefixTimeout) {
      clearTimeout(prefixTimeout);
      prefixTimeout = null;
    }
  }

  /**
   * Main keyboard handler
   */
  function handleKeydown(event) {
    const key = event.key.toLowerCase();

    // Debug logging (remove in production)
    if (window.stitch && window.stitch.debug) {
      console.log('[Stitch] Key pressed:', event.key, '| Lowercased:', key, '| Pending prefix:', pendingPrefix);
      console.log('[Stitch] Input focused:', isInputFocused(), '| Modifiers: ctrl=', event.ctrlKey, 'meta=', event.metaKey, 'alt=', event.altKey);
    }

    // Don't handle if in input (except Escape)
    if (isInputFocused() && event.key !== 'Escape') {
      if (window.stitch && window.stitch.debug) {
        console.log('[Stitch] Skipping - input is focused');
      }
      return;
    }

    // Don't handle if modifier keys (except for our shortcuts)
    if (event.ctrlKey || event.metaKey || event.altKey) {
      if (window.stitch && window.stitch.debug) {
        console.log('[Stitch] Skipping - modifier key held');
      }
      return;
    }

    // Handle pending 'g' prefix
    if (pendingPrefix === 'g') {
      clearPrefix();

      const url = SHORTCUTS.goto[key];
      if (url) {
        if (window.stitch && window.stitch.debug) {
          console.log('[Stitch] Executing: g', key, '-> navigate to', url);
        }
        event.preventDefault();
        navigateTo(url);
        return;
      } else {
        if (window.stitch && window.stitch.debug) {
          console.log('[Stitch] No goto shortcut for g', key);
        }
      }
    }

    // Start 'g' prefix
    if (key === 'g' && !pendingPrefix) {
      if (window.stitch && window.stitch.debug) {
        console.log('[Stitch] Prefix "g" started - waiting for next key...');
      }
      event.preventDefault();
      pendingPrefix = 'g';

      // Clear after 1 second
      prefixTimeout = setTimeout(function() {
        if (window.stitch && window.stitch.debug) {
          console.log('[Stitch] Prefix "g" timed out');
        }
        clearPrefix();
      }, 1000);
      return;
    }

    // Handle single-key shortcuts
    const handler = SHORTCUTS.single[event.key] || SHORTCUTS.single[key];
    if (handler) {
      if (window.stitch && window.stitch.debug) {
        console.log('[Stitch] Executing single-key shortcut:', key);
      }
      const handled = handler();
      if (handled) {
        event.preventDefault();
      }
    }
  }

  /**
   * Show keyboard shortcuts help (optional)
   */
  function showHelp() {
    console.log('%c Keyboard Shortcuts ', 'background: #0891b2; color: white; padding: 4px 8px; border-radius: 4px;');
    console.log('g h  - Go to Dashboard (/web/dashboard)');
    console.log('g u  - Go to Users (/web/admin/user)');
    console.log('n    - New/Create');
    console.log('e    - Edit');
    console.log('/    - Focus search');
    console.log('Esc  - Close/Cancel');
  }

  /**
   * Initialize keyboard shortcuts
   */
  function init() {
    document.addEventListener('keydown', handleKeydown);

    // Expose help function and enable debugging
    window.stitch = window.stitch || {};
    window.stitch.debug = true; // Enable debug by default for troubleshooting
    window.stitch.shortcuts = {
      help: showHelp,
      config: SHORTCUTS,
      test: function() {
        console.log('Keyboard shortcuts test - navigating to dashboard...');
        navigateTo('/web/dashboard');
      }
    };

    // Log initialization
    console.log('%c Stitch Keyboard Shortcuts loaded ', 'background: #0891b2; color: white; padding: 2px 6px; border-radius: 3px;');
    console.log('Type stitch.shortcuts.help() for available shortcuts');
    console.log('Debug mode enabled - key presses will be logged to console');
  }

  // Initialize when DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
