export const THEME_KEY = "bkstudio-theme";

// Runs synchronously in <head> (a plain <script>, not next/script, which defers behind async chunks):
// applies a stored light/dark choice before first paint; System relies on the CSS media query.
export const THEME_INIT = `try{var t=localStorage.getItem(${JSON.stringify(THEME_KEY)});if(t==="light"||t==="dark")document.documentElement.dataset.theme=t}catch(e){}`;
