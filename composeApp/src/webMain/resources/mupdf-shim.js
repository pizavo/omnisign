import * as mupdf from "mupdf";

export function init() {}

export function getPageCount(bytes) {
    const doc = mupdf.Document.openDocument(bytes, "application/pdf");
    try {
        return doc.countPages();
    } finally {
        doc.destroy();
    }
}

export function renderPagePng(bytes, pageIndex, scale) {
    const doc = mupdf.Document.openDocument(bytes, "application/pdf");
    try {
        const page = doc.loadPage(pageIndex);
        try {
            const pixmap = page.toPixmap(
                mupdf.Matrix.scale(scale, scale),
                mupdf.ColorSpace.DeviceRGB,
                false
            );
            try {
                return pixmap.asPNG();
            } finally {
                pixmap.destroy();
            }
        } finally {
            page.destroy();
        }
    } finally {
        doc.destroy();
    }
}
