// Stack a headed table when a column would be narrower than this at the reader font size.
const _minColumnEm = 7;

const _managedTables: HTMLTableElement[] = [];
const _originalCellHtml = new WeakMap<HTMLTableCellElement, string>();
const _originalTableHtml = new WeakMap<HTMLTableElement, string>();
let _watchingLayout = false;
let _layoutFrame = 0;
let _layoutRetries = 0;

// Responsive table styles applied to tables with headers
export function initResponsiveTables(): void {
  let tables = Array.from(document.querySelectorAll('table'));

  // Filter out tables with class 'docx-table' or 'transparent-table'
  tables = tables.filter(
    (table) => !table.classList.contains('docx-table') && !table.classList.contains('transparent-table') && !table.classList.contains('plain-table'),
  );

  // If the tables array is empty or null, do nothing and return early
  if (!tables || tables.length === 0) {
    return;
  }

  tables.forEach((table) => {
    // Identify if nonstandard table
    // All steps created for nonstandard tables have been created with copilot and had minor edits
    if (_checkIfMostlyEmptyTable(table)) {
      return;
    }

    const theadThCount = table.querySelectorAll('thead th').length;
    const tbodyTrs = table.querySelectorAll('tbody tr');
    const isNonStandardTable = theadThCount === 1 && Array.from(tbodyTrs).every((tr) => tr.querySelectorAll('td').length === 2);

    if (isNonStandardTable) {
      // If special table if it has headlines in the columns or first row
      const firstTbodyTr = tbodyTrs[0];
      const firstRowCells = firstTbodyTr.querySelectorAll('td');
      const hasStrongOrHeadlineTags = Array.from(firstRowCells).every(
        (td) => td.querySelector('strong') || Array.from({ length: 6 }, (_, i) => td.querySelector(`h${i + 1}`)).some((element) => element !== null),
      );

      // If headlines in the first row, make it a plain table
      if (hasStrongOrHeadlineTags) {
        table.classList.add('plain-table');
        return;
      }
    }

    if (!_originalTableHtml.has(table)) {
      _originalTableHtml.set(table, table.outerHTML);
    }
    if (isNonStandardTable) {
      _flattenTable(table);
    }

    _transformResponsiveTable(table);
    _wrapTable(table);
    _managedTables.push(table);
  });

  _relayoutManagedTables();
  _watchTableLayout();
}

function _transformResponsiveTable(table: HTMLTableElement) {
  let tableHeaderRow: HTMLTableRowElement | null = null;
  let tableHeaders: HTMLTableCellElement[] = [];

  // if the table has a table head
  if (table.tHead) {
    tableHeaderRow = table.tHead.rows[0];
    tableHeaders = Array.from(tableHeaderRow.cells);
  } else {
    // get the first row, whether it's in a tbody or not
    const firstRow = table.rows[0];
    if (firstRow && firstRow.querySelector('th, h1, h2, h3, h4, h5, h6, strong')) {
      tableHeaderRow = firstRow;
      tableHeaders = Array.from(firstRow.cells);
      table.classList.add('has-first-row-headers');
    }
  }

  // if the table has headers
  if (tableHeaders.length) {
    table.classList.add('has-header');

    const headers = tableHeaders.map((headerCell) => {
      let header = headerCell.textContent?.trim() || '';
      if (header.length) {
        header += ':'; // append colon to non-empty headers
      }
      return header;
    });

    Array.from(table.rows)
      .filter((row) => row !== tableHeaderRow)
      .forEach((row) => {
        Array.from(row.cells).forEach((cell, index) => {
          cell.setAttribute('data-th', headers[index] || '');
        });
      });
  }
}

function _wrapTable(table: HTMLTableElement): void {
  const parent = table.parentElement;
  if (!parent || parent.classList.contains('flutter-readium-table-frame')) {
    return;
  }

  const frame = document.createElement('div');
  frame.className = 'flutter-readium-table-frame';
  parent.insertBefore(frame, table);
  frame.appendChild(table);
}

function _columnCount(table: HTMLTableElement): number {
  let max = 0;
  Array.from(table.rows).forEach((row) => {
    let count = 0;
    Array.from(row.cells).forEach((cell) => {
      count += cell.colSpan || 1;
    });
    if (count > max) {
      max = count;
    }
  });
  return max;
}

function _hasMergedCells(table: HTMLTableElement): boolean {
  return Array.from(table.rows).some((row) => Array.from(row.cells).some((cell) => cell.colSpan > 1 || cell.rowSpan > 1));
}

function _isBookMode(): boolean {
  const style = document.documentElement.getAttribute('style') ?? '';
  const view = getComputedStyle(document.documentElement).getPropertyValue('--USER__view');
  return !style.includes('readium-scroll-on') && !view.includes('readium-scroll-on');
}

// In CSS columns, clientWidth is often the whole chapter. The fragment rect is one page;
// if that rect is the full webview, split the viewport by the column count instead.
function _pageWidth(frame: HTMLElement): number {
  const fragment = frame.getBoundingClientRect().width;
  const raw = (
    getComputedStyle(document.documentElement).getPropertyValue('--USER__colCount') ||
    getComputedStyle(document.documentElement).getPropertyValue('--RS__colCount')
  ).trim();
  const slice = window.innerWidth / (raw === '2' ? 2 : 1);
  if (fragment > 0 && fragment < window.innerWidth - 8) {
    return fragment;
  }
  return slice > 0 ? slice : fragment;
}

// Measure outside the column. A table's scrollWidth inside CSS columns is often
// just the page, so a wide grid looks like it fits and then paints onto the next one.
function _naturalWidth(table: HTMLTableElement): number {
  const probe = table.cloneNode(true) as HTMLTableElement;
  probe.setAttribute('aria-hidden', 'true');
  probe.style.cssText =
    'position:fixed;left:0;top:0;visibility:hidden;pointer-events:none;width:max-content;max-width:none;height:auto;margin:0;';
  document.documentElement.appendChild(probe);
  const width = Math.max(probe.scrollWidth, probe.getBoundingClientRect().width);
  probe.remove();
  return width;
}

function _backgroundColor(): string {
  const body = getComputedStyle(document.body).backgroundColor;
  if (body && body !== 'transparent' && body !== 'rgba(0, 0, 0, 0)') {
    return body;
  }
  return getComputedStyle(document.documentElement).backgroundColor;
}

function _openTable(table: HTMLTableElement): void {
  const opener = (window as Window & { flutterReadiumOpenTable?: (json: string) => void }).flutterReadiumOpenTable;
  if (!opener) {
    return;
  }
  const body = getComputedStyle(document.body);
  opener(
    JSON.stringify({
      html: _originalTableHtml.get(table) ?? table.outerHTML,
      textColor: body.color,
      backgroundColor: _backgroundColor(),
      fontFamily: body.fontFamily,
      fontSizePx: parseFloat(getComputedStyle(document.documentElement).fontSize) || 16,
    }),
  );
}

function _setViewerButton(table: HTMLTableElement, show: boolean): void {
  const frame = table.parentElement;
  if (!frame) {
    return;
  }
  const existing = Array.from(frame.children).find((child) => child.classList.contains('flutter-readium-table-open'));
  if (!show) {
    existing?.remove();
    return;
  }
  if (existing) {
    return;
  }

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'flutter-readium-table-open';
  button.setAttribute('aria-label', 'View full table');
  button.innerHTML =
    '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const keep = (event: Event) => event.stopPropagation();
  button.addEventListener('pointerdown', keep);
  button.addEventListener('touchstart', keep);
  button.addEventListener('touchend', keep);
  button.addEventListener('click', (event) => {
    event.preventDefault();
    event.stopPropagation();
    _openTable(table);
  });
  frame.insertBefore(button, table);
}

// Book mode fits the table to the page. Scroll mode keeps columns until one would be under ~7em.
function _relayoutManagedTables(): void {
  const rootFontSize = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
  let waitingForLayout = false;

  _managedTables.forEach((table) => {
    if (!table.isConnected) {
      return;
    }
    const frame = table.parentElement;
    if (!frame?.classList.contains('flutter-readium-table-frame')) {
      return;
    }

    _restoreMobileHeaders(table);
    table.classList.remove('is-stacked');
    frame.classList.remove('is-contained', 'is-page-clipped');

    if (_isBookMode()) {
      const pageWidth = _pageWidth(frame);
      if (pageWidth <= 0) {
        waitingForLayout = true;
        return;
      }
      const overflows = _naturalWidth(table) > pageWidth + 1;
      const canStack = table.classList.contains('has-header') && !_hasMergedCells(table);
      if (overflows && canStack) {
        table.classList.add('is-stacked');
        frame.classList.add('is-contained');
        _applyMobileHeaders(table);
      } else if (overflows) {
        frame.classList.add('is-page-clipped');
      }
      _setViewerButton(table, overflows);
      return;
    }

    _setViewerButton(table, false);
    const available = frame.clientWidth;
    if (available <= 0) {
      waitingForLayout = true;
      return;
    }
    if (!table.classList.contains('has-header') || _hasMergedCells(table)) {
      return;
    }
    const columns = _columnCount(table);
    if (columns >= 2 && available / columns < rootFontSize * _minColumnEm) {
      table.classList.add('is-stacked');
      _applyMobileHeaders(table);
    }
  });

  if (waitingForLayout && _layoutRetries < 5) {
    _layoutRetries += 1;
    _scheduleRelayout();
    return;
  }
  _layoutRetries = 0;
}

function _applyMobileHeaders(table: HTMLTableElement): void {
  Array.from(table.rows).forEach((row) => {
    if (row.parentElement?.tagName.toLowerCase() === 'thead') {
      return;
    }
    if (table.classList.contains('has-first-row-headers') && row.rowIndex === 0) {
      return;
    }

    const cell = row.cells[0];
    // An empty data-th means this cell is a row label, not a value under a column header.
    if (!cell || cell.getAttribute('data-th') || cell.classList.contains('mobile-header')) {
      return;
    }

    _originalCellHtml.set(cell, cell.innerHTML);
    const h6 = document.createElement('h6');
    while (cell.firstChild) {
      h6.appendChild(cell.firstChild);
    }
    cell.appendChild(h6);
    cell.classList.add('mobile-header');
  });
}

function _restoreMobileHeaders(table: HTMLTableElement): void {
  table.querySelectorAll('td.mobile-header, th.mobile-header').forEach((cell) => {
    if (!(cell instanceof HTMLTableCellElement)) {
      return;
    }
    const html = _originalCellHtml.get(cell);
    if (html == null) {
      cell.classList.remove('mobile-header');
      return;
    }
    cell.innerHTML = html;
    cell.classList.remove('mobile-header');
    _originalCellHtml.delete(cell);
  });
}

function _scheduleRelayout(): void {
  if (_layoutFrame) {
    return;
  }
  _layoutFrame = requestAnimationFrame(() => {
    _layoutFrame = 0;
    _relayoutManagedTables();
  });
}

function _watchTableLayout(): void {
  if (_watchingLayout || _managedTables.length === 0) {
    return;
  }
  _watchingLayout = true;

  // Font size and column count update the inline style on :root without reloading the chapter.
  const observer = new MutationObserver(_scheduleRelayout);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['style'] });

  if (typeof ResizeObserver !== 'undefined') {
    const resizeObserver = new ResizeObserver(_scheduleRelayout);
    resizeObserver.observe(document.documentElement);
    _managedTables.forEach((table) => {
      if (table.parentElement) {
        resizeObserver.observe(table.parentElement);
      }
    });
    return;
  }

  window.addEventListener('resize', _scheduleRelayout);
}

function _checkIfMostlyEmptyTable(table: HTMLTableElement) {
  let emptyCells = 0;
  let nonEmptyCells = 0;

  //  Iterate through all rows and cells
  table.querySelectorAll('tr').forEach((row) => {
    row.querySelectorAll('td').forEach((cell) => {
      // Check if cell is empty
      if (cell.textContent.trim() === '') {
        emptyCells++;
      } else {
        nonEmptyCells++;
      }
    });
  });

  // Compare counts and modify table if necessary
  if (emptyCells > nonEmptyCells) {
    table.classList.add('plain-table');
    return true;
  }
  return false;
}

function _flattenTable(table: HTMLTableElement) {
  // If headlines in the columns, transform the table so that the script following can transform it to match the other tables correctly
  const theadThText = table.querySelector('thead th')!.textContent;
  const tbodyTds = Array.from(table.querySelectorAll('tbody td'));

  // Clear the table and start the transformation
  table.innerHTML = '';

  const newTbody = document.createElement('tbody');
  const headerRow = document.createElement('tr');
  const contentRow = document.createElement('tr');

  headerRow.innerHTML = '<td>&#160;</td>'; // Initial empty cell
  contentRow.innerHTML = `<td>${theadThText}</td>`; // Thead text becomes the first cell in the second row

  tbodyTds.forEach((td, index) => {
    if (index % 2 === 0) {
      // Even index, header row
      const headerCell = document.createElement('td');
      headerCell.innerHTML = `<h3>${td.textContent}</h3>`;
      headerRow.appendChild(headerCell);
    } else {
      // Odd index, content row
      const contentCell = document.createElement('td');
      // Directly append the content to the contentCell
      Array.from(td.childNodes).forEach((child) => contentCell.appendChild(child.cloneNode(true)));
      contentRow.appendChild(contentCell);
    }
  });

  newTbody.appendChild(headerRow);
  newTbody.appendChild(contentRow);
  table.appendChild(newTbody);

  return table;
}
