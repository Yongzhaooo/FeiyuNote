CREATE TABLE templates (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    instruction TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE notebooks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL CHECK (kind IN ('course', 'practice')),
    name TEXT NOT NULL,
    linked_course_id INTEGER REFERENCES notebooks(id) ON DELETE SET NULL,
    default_template_id INTEGER REFERENCES templates(id) ON DELETE SET NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE lessons (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    notebook_id INTEGER NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    lesson_id INTEGER NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('user', 'assistant', 'note')),
    action TEXT,
    text TEXT NOT NULL,
    parent_entry_id INTEGER REFERENCES entries(id) ON DELETE CASCADE,
    source_entry_ids TEXT NOT NULL DEFAULT '[]',
    image_paths TEXT NOT NULL DEFAULT '[]',
    attached_image_entry_ids TEXT NOT NULL DEFAULT '[]',
    template_id INTEGER,
    state TEXT,
    archived INTEGER NOT NULL DEFAULT 0,
    mastery TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX idx_lessons_notebook ON lessons(notebook_id);
CREATE INDEX idx_entries_lesson ON entries(lesson_id);
CREATE INDEX idx_entries_parent ON entries(parent_entry_id);
