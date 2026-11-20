-- More upload formats: PDF, Word 97-2003, RTF, OpenDocument, HTML and Markdown.
ALTER TABLE transcript_revisions DROP CONSTRAINT transcript_revisions_source_type_check;
ALTER TABLE transcript_revisions ADD CONSTRAINT transcript_revisions_source_type_check
    CHECK (source_type IN ('PASTE','TXT','MD','DOCX','DOC','PDF','RTF','ODT','HTML','VTT','SRT','AUDIO'));
