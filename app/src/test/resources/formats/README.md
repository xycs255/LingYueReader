这些测试用的小书由 Calibre 7.6 从一份三章的 HTML 转出（`ebook-convert book.epub book.mobi` 等），
内容是测试时写的几句话和一张 40×20 的红色 PNG：

- `book.mobi`：老式 MOBI（PalmDOC 压缩，正文末尾有目录页）
- `book.azw3`：KF8
- `book-both.mobi`：MOBI + KF8 合在一个文件里（`--mobi-file-type both`）
- `book.fb2`：FictionBook 2，插图以 base64 内嵌
