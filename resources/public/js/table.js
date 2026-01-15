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
    const selectionCount = document.getElementById('selection-count');
    const selectAllCheckbox = document.getElementById('select-all');
    const rowCheckboxes = document.querySelectorAll('input[name="record-ids"]');

    if (!bulkDeleteBtn || !selectionCount) return; // Not on a list page

    // Update selection count and button state
    function updateSelectionState() {
      // Re-query to get current state (in case checkboxes were replaced)
      const currentCheckboxes = document.querySelectorAll('input[name="record-ids"]');
      const checkedCount = Array.from(currentCheckboxes).filter(cb => cb.checked).length;
      
      // Update count text
      selectionCount.textContent = `${checkedCount} selected`;
      
      // Enable/disable bulk delete button
      if (checkedCount > 0) {
        bulkDeleteBtn.disabled = false;
      } else {
        bulkDeleteBtn.disabled = true;
      }
    }

    // Remove old event listeners by cloning elements (if they exist)
    // This prevents duplicate listeners from causing issues
    if (selectAllCheckbox && selectAllCheckbox._hasListener) {
      return; // Already initialized, don't re-attach
    }

    // Handle select-all checkbox
    if (selectAllCheckbox) {
      selectAllCheckbox._hasListener = true;
      selectAllCheckbox.addEventListener('change', function(e) {
        const currentCheckboxes = document.querySelectorAll('input[name="record-ids"]');
        currentCheckboxes.forEach(cb => {
          cb.checked = this.checked;
        });
        updateSelectionState();
      });
    }

    // Handle individual row checkboxes
    rowCheckboxes.forEach(checkbox => {
      if (!checkbox._hasListener) {
        checkbox._hasListener = true;
        checkbox.addEventListener('change', function(e) {
          // Update select-all checkbox state
          const currentCheckboxes = document.querySelectorAll('input[name="record-ids"]');
          const currentSelectAll = document.getElementById('select-all');
          if (currentSelectAll) {
            const allChecked = Array.from(currentCheckboxes).every(cb => cb.checked);
            const someChecked = Array.from(currentCheckboxes).some(cb => cb.checked);
            currentSelectAll.checked = allChecked;
            currentSelectAll.indeterminate = someChecked && !allChecked;
          }
          updateSelectionState();
        });
      }
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
