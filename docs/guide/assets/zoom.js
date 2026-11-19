// A click on a picture of the editor shows it big over the page; a click anywhere or Esc closes it.
(function () {
  var pictures = ".md-typeset img.shot, .md-typeset img.wide";

  var opener = null;

  function close() {
    var open = document.querySelector(".guide-zoom");
    if (open) {
      open.remove();
      if (opener && document.contains(opener)) {
        opener.focus();
      }
      opener = null;
    }
    return Boolean(open);
  }

  function show(picture) {
    var over = document.createElement("div");
    over.className = "guide-zoom";
    over.setAttribute("role", "dialog");
    over.setAttribute("aria-modal", "true");
    over.tabIndex = -1;
    over.setAttribute("aria-label", picture.alt);
    var big = document.createElement("img");
    big.src = picture.currentSrc || picture.src;
    big.alt = picture.alt;
    over.appendChild(big);
    document.body.appendChild(over);
    opener = picture;
    over.focus();
  }

  document.addEventListener("click", function (event) {
    if (close()) {
      return;
    }
    var picture = event.target.closest ? event.target.closest(pictures) : null;
    if (picture) {
      show(picture);
    }
  });

  document.addEventListener("keydown", function (event) {
    var opens = event.key === "Enter" || event.key === " ";
    if (document.querySelector(".guide-zoom")) {
      // any of these keys closes the big picture; Tab stays on it
      if (event.key === "Escape" || opens || event.key === "Tab") {
        event.preventDefault();
        if (event.key !== "Tab") {
          close();
        }
      }
    } else if (opens && event.target.matches && event.target.matches(pictures)) {
      event.preventDefault();
      show(event.target);
    }
  });

  // reachable by keyboard, also on pages loaded without a reload
  function reachable() {
    // a page reached by Back or a link starts without a big picture of the last one
    close();
    document.querySelectorAll(pictures).forEach(function (picture) {
      picture.tabIndex = 0;
      picture.setAttribute("role", "button");
      picture.title = "Show bigger";
    });
  }

  if (window.document$) {
    window.document$.subscribe(reachable);
  } else {
    document.addEventListener("DOMContentLoaded", reachable);
  }
})();
