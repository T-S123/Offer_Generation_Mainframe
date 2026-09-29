# Local policy documents

Add PDF, DOC/DOCX, TXT, Markdown, HTML, RTF or ODT documents here. Documents remain local and are ignored by Git. Set AI_POLICY_DIR to use another folder.

The Research service detects the actual file format using Apache Tika, extracts searchable text in a separate process, records content hashes and quality status, and reindexes changed files. Limits: 20 MB/file, 200 documents, 1 million characters/file and 30 seconds/parser process. Embedded attachments are not parsed.

In the mainframe, choose Business > 08 AI-assisted simulation > 3 Review local policy documents. Refresh with R, inspect the extracted text, then approve the exact document version, product and expiry before Research can rely on it. The local engine supports US geography. The policy review API provides the same operation for integrations. Editing a document revokes its prior approval. Empty, truncated or unreadable extractions cannot be approved.

For scanned PDFs, install Tesseract in the Java runtime environment and set AI_ENABLE_OCR=true. Otherwise scans are labeled OCR_OR_REVIEW_REQUIRED. OCR output still needs analyst review. The service never treats unreadable content as policy evidence.

Do not add credentials or customer records. Approved excerpts can be included in model requests; full documents stay in the local index.
