/* global Terminal, FitAddon, ResizeObserver */

(() => {
  const post = (event) =>
    window.HermesTerminalSurface.postMessage(JSON.stringify(event));

  try {
    const container = document.getElementById('terminal');
    const terminal = new Terminal({
      fontFamily: 'monospace',
      fontSize: 13,
      cursorBlink: true,
      disableStdin: true,
      altClickMovesCursor: false,
      scrollback: 2000,
      logLevel: 'off',
      linkHandler: {activate() {}},
      theme: {
        background: '#17151b',
        foreground: '#f3eef5',
        cursor: '#d0a5ff',
        selectionBackground: '#453651',
      },
    });

    terminal.parser.registerOscHandler(8, () => true);
    terminal.parser.registerOscHandler(52, () => true);
    for (const name of ['copy', 'cut', 'paste', 'contextmenu', 'drop']) {
      document.addEventListener(
        name,
        (event) => {
          event.preventDefault();
          event.stopImmediatePropagation();
        },
        true,
      );
    }

    const fit = new FitAddon.FitAddon();
    terminal.loadAddon(fit);
    terminal.open(container);
    terminal.textarea.readOnly = true;
    terminal.textarea.setAttribute('aria-label', '终端键盘输入');
    let activeView = null;
    let replayWrites = 0;
    terminal.onData((data) => {
      // A historic query may cause xterm to emit a reply while parsing the replay.
      if (activeView !== null && replayWrites === 0) {
        for (const chunk of data.match(/[\s\S]{1,8192}/gu) ?? []) {
          post({kind: 'input', data: chunk, viewId: activeView});
        }
      }
    });

    const controls = {
      escape: '\x1b',
      tab: '\t',
      interrupt: '\x03',
      enter: '\r',
    };
    const arrows = {up: 'A', down: 'B', right: 'C', left: 'D'};
    let generation = 0;

    window.hermesTerminal = {
      setInput(viewId) {
        activeView = viewId;
        terminal.options.disableStdin = viewId === null;
        terminal.textarea.readOnly = viewId === null;
        if (viewId === null) {
          terminal.blur();
        }
      },
      reset() {
        generation += 1;
        terminal.write('', () => terminal.reset());
      },
      clear() {
        terminal.clear();
      },
      focus() {
        if (!terminal.options.disableStdin) terminal.focus();
      },
      copyText() {
        const selection = terminal.getSelection();
        if (selection) return selection;
        const buffer = terminal.buffer.active;
        const lines = [];
        for (let row = 0; row < terminal.rows; row += 1) {
          lines.push(
            buffer.getLine(buffer.viewportY + row)?.translateToString(true) ?? '',
          );
        }
        while (lines.length && !lines[lines.length - 1]) lines.pop();
        return lines.join('\n');
      },
      paste(data) {
        if (!terminal.options.disableStdin) terminal.paste(data);
      },
      write(data, sequence, viewId, replay) {
        const current = generation;
        const bytes = Uint8Array.from(window.atob(data), (byte) =>
          byte.charCodeAt(0),
        );
        if (replay) replayWrites += 1;
        terminal.write(bytes, () => {
          if (replay) replayWrites -= 1;
          if (current === generation) {
            post({kind: 'written', sequence, viewId});
          }
        });
      },
      key(name) {
        const arrow = arrows[name];
        const data = arrow
          ? `\x1b${terminal.modes.applicationCursorKeysMode ? 'O' : '['}${arrow}`
          : controls[name];
        if (data && !terminal.options.disableStdin) {
          terminal.input(data, true);
        }
      },
    };

    let cols = 0;
    let rows = 0;
    function resize() {
      fit.fit();
      if (terminal.cols !== cols || terminal.rows !== rows) {
        cols = terminal.cols;
        rows = terminal.rows;
        post({kind: 'size', cols, rows});
      }
    }
    new ResizeObserver(resize).observe(container);
    resize();
  } catch {
    post({kind: 'error'});
  }
})();
