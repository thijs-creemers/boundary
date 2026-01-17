/**
 * Table Interactions
 * 
 * Handles:
 * - Bulk selection tracking
 * - Selection count updates
 * - Bulk action button state management
 */

(function() {
  'use strict';

  // Initialize table interactions when DOM is ready
  function initTableInteractions() {
    const bulkDeleteBtn = document.getElementById('bulk-delete-btn');
    const selectAllCheckbox = document.getElementById('select-all');
    const rowCheckboxes = document.querySelectorAll('input[name="ids[]"]');

    if (!bulkDeleteBtn) return; // Not on a list page

    // Update button state based on selection
    function updateSelectionState() {
      const checkedCount = Array.from(rowCheckboxes).filter(cb => cb.checked).length;
      
      // Simply enable/disable the button (no visibility toggling)
      bulkDeleteBtn.disabled = checkedCount === 0;
    }

    // Handle select-all checkbox
    if (selectAllCheckbox) {
      selectAllCheckbox.addEventListener('change', function() {
        rowCheckboxes.forEach(cb => {
          cb.checked = this.checked;
        });
        updateSelectionState();
      });
    }

    // Handle individual row checkboxes
    rowCheckboxes.forEach(checkbox => {
      checkbox.addEventListener('change', function() {
        // Update select-all checkbox state
        if (selectAllCheckbox) {
          const allChecked = Array.from(rowCheckboxes).every(cb => cb.checked);
          const someChecked = Array.from(rowCheckboxes).some(cb => cb.checked);
          selectAllCheckbox.checked = allChecked;
          selectAllCheckbox.indeterminate = someChecked && !allChecked;
        }
        updateSelectionState();
      });
    });

    // Initialize state
    updateSelectionState();
  }

  // Initialize on page load
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initTableInteractions);
  } else {
    initTableInteractions();
  }

  // Re-initialize after HTMX updates
  document.body.addEventListener('htmx:afterSwap', function(event) {
    // Only reinitialize if the table was updated
    if (event.target.id === 'entity-table-container') {
      setTimeout(initTableInteractions, 0);
    }
  });

})();
