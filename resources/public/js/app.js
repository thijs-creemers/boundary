/**
 * Main application JavaScript
 * General UI interactions and utilities
 */

// User dropdown toggle
function toggleUserDropdown() {
  const menu = document.getElementById('user-dropdown-menu');
  if (menu) {
    menu.style.display = menu.style.display === 'none' ? 'block' : 'none';
  }
}

// Close dropdown when clicking outside
document.addEventListener('click', function(event) {
  const dropdown = document.querySelector('.user-dropdown');
  const menu = document.getElementById('user-dropdown-menu');
  
  if (dropdown && menu && !dropdown.contains(event.target)) {
    menu.style.display = 'none';
  }
});

// Close dropdown on Escape key
document.addEventListener('keydown', function(event) {
  if (event.key === 'Escape') {
    const menu = document.getElementById('user-dropdown-menu');
    if (menu) {
      menu.style.display = 'none';
    }
  }
});
