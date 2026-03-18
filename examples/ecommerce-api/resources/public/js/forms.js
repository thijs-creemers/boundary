/**
 * Form Interactions
 * 
 * Handles:
 * - Form submission success redirects
 * - Form validation feedback
 * - HTMX form event handling
 */

(function() {
  'use strict';

  // Handle successful form submissions
  // NOTE: Using HX-Redirect header from server instead of client-side redirect
  document.body.addEventListener('htmx:afterRequest', function(event) {
    // HTMX will automatically handle HX-Redirect header
  });

  // Handle form validation errors
  document.body.addEventListener('htmx:responseError', function(event) {
    const form = event.detail.elt;
    if (form.tagName !== 'FORM') return;
    
    // Could add custom error handling here
  });

})();
