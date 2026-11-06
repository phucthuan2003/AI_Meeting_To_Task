ALTER TABLE transcript_revisions DROP CONSTRAINT transcript_revisions_source_type_check;
ALTER TABLE transcript_revisions ADD CONSTRAINT transcript_revisions_source_type_check
    CHECK (source_type IN ('PASTE', 'TXT', 'DOCX', 'VTT', 'SRT'));
