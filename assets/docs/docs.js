/**
 * Progressive enhancement for the published documentation.
 *
 * 1. Fenced ```mermaid blocks become diagrams. Mermaid is loaded from a CDN by the
 *    layout; if that script never arrives, the original code block stays on the page and
 *    gets a short note explaining what a reader is looking at.
 * 2. Every diagram keeps its source in a collapsed <details> element, so the text of the
 *    diagram is reachable without the rendered SVG.
 * 3. Wide tables get a scroll container so a long row cannot push the page sideways.
 *
 * No modules, no build step, no second dependency: this file is served as-is by GitHub
 * Pages.
 */
(function () {
  "use strict";

  var MERMAID_THEME = {
    theme: "base",
    fontFamily:
      'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
    themeVariables: {
      background: "#121716",
      primaryColor: "#171d1c",
      primaryBorderColor: "#ffb454",
      primaryTextColor: "#e8efec",
      secondaryColor: "#171d1c",
      tertiaryColor: "#0e1211",
      mainBkg: "#171d1c",
      nodeBorder: "#ffb454",
      clusterBkg: "#121716",
      clusterBorder: "#313b38",
      titleColor: "#e8efec",
      textColor: "#e8efec",
      lineColor: "#73817d",
      edgeLabelBackground: "#0e1211",
      labelTextColor: "#e8efec",
      actorBkg: "#171d1c",
      actorBorder: "#ffb454",
      actorTextColor: "#e8efec",
      actorLineColor: "#313b38",
      signalColor: "#9daba7",
      signalTextColor: "#e8efec",
      labelBoxBkgColor: "#171d1c",
      labelBoxBorderColor: "#ffb454",
      noteBkgColor: "#ffb454",
      noteBorderColor: "#ffb454",
      noteTextColor: "#0b0e0d",
      sequenceNumberColor: "#0b0e0d",
      errorBkgColor: "#ff7a5c",
      errorTextColor: "#0b0e0d"
    },
    flowchart: { useMaxWidth: true, htmlLabels: true, curve: "basis" },
    sequence: { useMaxWidth: true, mirrorActors: false, wrap: true },
    securityLevel: "strict",
    startOnLoad: false
  };

  function trim(value) {
    return (value || "").replace(/^\s+|\s+$/g, "");
  }

  /**
   * One root element per fenced ```mermaid block, whatever shape kramdown emitted:
   * a bare `pre > code.language-mermaid`, or a `div.language-mermaid.highlighter-rouge`
   * wrapper that may or may not also mark the inner `code`. When both elements carry the
   * marker, only the outermost one is kept, so a block is never rendered twice.
   */
  function mermaidRoots() {
    var marked = document.querySelectorAll("code.language-mermaid, .language-mermaid");
    var roots = [];

    Array.prototype.forEach.call(marked, function (element) {
      var root = element.tagName === "CODE" ? element.closest("pre") || element : element;

      while (
        root.parentElement &&
        String(root.parentElement.className || "").indexOf("language-mermaid") !== -1
      ) {
        root = root.parentElement;
      }

      var covered = roots.some(function (other) {
        return other === root || other.contains(root);
      });
      if (!covered) {
        roots.push(root);
      }
    });

    return roots;
  }

  function sourceDetails(source) {
    var details = document.createElement("details");
    details.className = "diagram-source";

    var summary = document.createElement("summary");
    summary.textContent = "Diagram source";

    var pre = document.createElement("pre");
    var code = document.createElement("code");
    code.textContent = source;
    pre.appendChild(code);

    details.appendChild(summary);
    details.appendChild(pre);
    return details;
  }

  function fallbackNote() {
    var note = document.createElement("p");
    note.className = "diagram-fallback";
    note.textContent =
      "The diagram could not be drawn because its renderer did not load. The source below is the complete diagram.";
    return note;
  }

  /**
   * Replace one fenced block with a figure that Mermaid can render in place. The figure
   * carries the source of the diagram, and the caller decides what to append to it.
   */
  function toDiagram(block, index) {
    var source = trim(block.textContent);
    if (!source) {
      return null;
    }

    var figure = document.createElement("figure");
    figure.className = "diagram";
    figure.setAttribute("data-diagram", String(index));

    var target = document.createElement("div");
    target.className = "mermaid";
    target.textContent = source;

    figure.appendChild(target);
    block.replaceWith(figure);

    return { figure: figure, target: target, source: source };
  }

  function wrapTables() {
    var tables = document.querySelectorAll(".doc > table");
    Array.prototype.forEach.call(tables, function (table) {
      var wrapper = document.createElement("div");
      wrapper.className = "table-scroll";
      table.replaceWith(wrapper);
      wrapper.appendChild(table);
    });
  }

  function renderDiagrams() {
    var roots = mermaidRoots();
    if (!roots.length) {
      return;
    }

    // Without the renderer the page keeps the original fenced blocks, which are already
    // readable; the note only explains why they look like code.
    if (!window.mermaid) {
      roots.forEach(function (root) {
        root.classList.add("diagram-fallback-block");
        root.parentNode.insertBefore(fallbackNote(), root);
      });
      return;
    }

    var diagrams = [];
    roots.forEach(function (root, index) {
      var diagram = toDiagram(root, index);
      if (diagram) {
        diagrams.push(diagram);
      }
    });

    if (!diagrams.length) {
      return;
    }

    window.mermaid.initialize(MERMAID_THEME);

    var nodes = diagrams.map(function (diagram) {
      return diagram.target;
    });

    window.mermaid
      .run({ nodes: nodes, suppressErrors: true })
      .then(function () {
        diagrams.forEach(function (diagram) {
          if (diagram.target.querySelector("svg")) {
            diagram.target.setAttribute("role", "img");
            diagram.figure.appendChild(sourceDetails(diagram.source));
          } else {
            restore(diagram);
          }
        });
      })
      .catch(function () {
        diagrams.forEach(restore);
      });
  }

  /** Put the fenced block back when Mermaid answered with nothing usable. */
  function restore(diagram) {
    var figure = diagram.figure;
    var pre = document.createElement("pre");
    var code = document.createElement("code");
    code.textContent = diagram.source;
    pre.appendChild(code);

    var block = document.createElement("div");
    block.className = "diagram-fallback-block";
    block.appendChild(fallbackNote());
    block.appendChild(pre);

    figure.replaceWith(block);
  }

  var initialized = false;

  function init() {
    if (initialized) {
      return;
    }
    initialized = true;

    renderDiagrams();
    wrapTables();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
