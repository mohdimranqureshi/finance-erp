import { Download, Printer } from 'lucide-react';

export type Column<T> = { header: string; value: (row: T) => string | number | null | undefined };

/** Spreadsheets execute cells starting with these, so user text is prefixed with a quote. */
const csvCell = (value: unknown) => {
  const text = value === null || value === undefined ? '' : String(value);
  const safe = /^[=+\-@\t\r]/.test(text) ? `'${text}` : text;
  return `"${safe.replace(/"/g, '""')}"`;
};

export function downloadCsv<T>(filename: string, columns: Column<T>[], rows: T[]) {
  const header = columns.map(c => csvCell(c.header)).join(',');
  const body = rows.map(row => columns.map(c => csvCell(c.value(row))).join(','));
  const csv = [header, ...body].join('\r\n');
  const url = URL.createObjectURL(new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename.endsWith('.csv') ? filename : `${filename}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

/** Prints one panel through the browser, which is also how the user saves a PDF. */
export function printSection(id: string) {
  const node = document.getElementById(id);
  if (!node) { window.print(); return; }
  node.classList.add('print-target');
  document.body.classList.add('printing');
  const cleanup = () => {
    node.classList.remove('print-target');
    document.body.classList.remove('printing');
    window.removeEventListener('afterprint', cleanup);
  };
  window.addEventListener('afterprint', cleanup);
  window.print();
}

export function ExportBar<T>({ filename, columns, rows, printId }: {
  filename: string; columns: Column<T>[]; rows: T[]; printId: string;
}) {
  return <span className="inline exportbar">
    <button type="button" className="small secondary" disabled={!rows.length}
      onClick={() => downloadCsv(filename, columns, rows)}><Download size={13} />CSV</button>
    <button type="button" className="small secondary" onClick={() => printSection(printId)}>
      <Printer size={13} />PDF
    </button>
  </span>;
}
