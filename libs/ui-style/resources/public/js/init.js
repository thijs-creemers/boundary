// Boundary Framework — Early Init Script
// Loaded as a blocking script in <head> before CSS for FOUC prevention.
//
// 1. Theme detection: reads localStorage or system preference, sets data-theme
//    attribute on <html> before stylesheets parse.
// 2. Async CSS promotion: converts print-media stylesheets to all-media after
//    DOM is ready (works with the media="print" async CSS loading pattern).

try{var t=localStorage.getItem('boundary-theme')||((window.matchMedia&&window.matchMedia('(prefers-color-scheme:dark)').matches)?'dark':'light');document.documentElement.setAttribute('data-theme',t)}catch(e){}
document.addEventListener('DOMContentLoaded',function(){for(var l=document.querySelectorAll('link[media=print]'),i=0;i<l.length;i++)l[i].media='all'})
