package com.feiyu.notes.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feiyu.notes.study.TurnContext
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CourseFeatureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "course-feature-test.db"
    private lateinit var database: NotebookDatabase
    private lateinit var store: NotebookStore
    @Before fun open() { context.deleteDatabase(name); connect() }
    private fun connect() { database = NotebookDatabase(context, name); store = NotebookStore(database, PhotoFiles(File(context.cacheDir, "course-feature-test"))) }
    @After fun close() { database.close(); context.deleteDatabase(name) }

    @Test fun migrationFromV4PreservesIdsReviewTemplatesPhotosAndAncestry() = runBlocking {
        database.close()
        val db = context.openOrCreateDatabase(name, 0, null)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("notes-v4.sql").bufferedReader(Charsets.UTF_8).use { it.readText() }
        schema.split(';').filter { it.isNotBlank() }.forEach(db::execSQL)
        db.execSQL("INSERT INTO templates VALUES(7, 'template', 'instruction', 1, NULL)")
        db.execSQL("INSERT INTO notebooks VALUES(11, 'course', 'legacy', NULL, 7, 1)")
        db.execSQL("INSERT INTO lessons VALUES(12, 11, '2026-10-12', 1)")
        db.execSQL("INSERT INTO entries(id,lesson_id,kind,action,text,image_paths,template_id,created_at) VALUES(13,12,'user','ask','question','[\"synthetic.jpg\"]',7,1)")
        db.execSQL("INSERT INTO entries(id,lesson_id,kind,text,parent_entry_id,state,created_at) VALUES(14,12,'assistant','answer',13,'complete',2)")
        db.execSQL("INSERT INTO review_records VALUES(15,11,'review','notes',14,0,'confused',1,1)")
        db.version = 4; db.close(); connect()
        assertEquals("", store.getLesson(12)!!.scratchpad)
        assertEquals(13L, store.getEntry(14)!!.parentEntryId)
        assertEquals(listOf("synthetic.jpg"), store.getEntry(13)!!.imagePaths)
        assertEquals(TurnContext.EMPTY, store.getEntry(13)!!.requestContext)
        assertEquals(7L, store.getEntry(13)!!.templateId)
        assertEquals(ReviewStatus.CONFUSED, store.getReviewRecord(11, 15)!!.status)
        assertEquals("instruction", store.getTemplate(7)!!.instruction)
        assertEquals(NotebookDatabase.VERSION, database.readableDatabase.version)
    }

    @Test fun intermediateV6HandbookUpgradePreservesSourceIndexAndBookmarks() = runBlocking {
        database.close()
        context.deleteDatabase(name)
        val db = context.openOrCreateDatabase(name, 0, null)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("notes-v4.sql").bufferedReader(Charsets.UTF_8).use { it.readText() }
        schema.split(';').filter { it.isNotBlank() }.forEach(db::execSQL)
        db.execSQL("ALTER TABLE lessons ADD COLUMN scratchpad TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE entries ADD COLUMN request_context TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("CREATE TABLE course_documents(id INTEGER PRIMARY KEY AUTOINCREMENT, notebook_id INTEGER NOT NULL UNIQUE REFERENCES notebooks(id) ON DELETE CASCADE, markdown TEXT NOT NULL, revision INTEGER NOT NULL, UNIQUE(id,notebook_id))")
        db.execSQL("CREATE TABLE course_sections(id INTEGER PRIMARY KEY AUTOINCREMENT,document_id INTEGER NOT NULL,notebook_id INTEGER NOT NULL,heading_path TEXT NOT NULL,markdown TEXT NOT NULL,ordinal INTEGER NOT NULL,UNIQUE(id,notebook_id),FOREIGN KEY(document_id,notebook_id) REFERENCES course_documents(id,notebook_id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE course_bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT,notebook_id INTEGER NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,section_id INTEGER,heading_path TEXT NOT NULL,FOREIGN KEY(section_id,notebook_id) REFERENCES course_sections(id,notebook_id))")
        db.execSQL("CREATE VIRTUAL TABLE course_sections_fts USING fts4(tokens, tokenize=unicode61)")
        db.execSQL("INSERT INTO notebooks(id,kind,name,created_at) VALUES(1,'course','legacy',1)")
        db.execSQL("INSERT INTO course_documents VALUES(2,1,'# 极限\n连续前提',1)")
        db.execSQL("INSERT INTO course_sections VALUES(3,2,1,'极限','# 极限\n连续前提',0)")
        db.execSQL("INSERT INTO course_bookmarks VALUES(4,1,3,'极限')")
        db.execSQL("INSERT INTO course_sections_fts(docid,tokens) VALUES(3,'极限 连续 前提')")
        db.version = 6; db.close(); connect()
        assertTrue(store.getCourseDocument(1)!!.enabled)
        assertEquals("# 极限\n连续前提", store.getCourseDocument(1)!!.markdown)
        assertEquals(3L, store.readCourseBookmarks(1).single().sectionId)
        assertEquals(3L, store.searchCourseKnowledge(1, "极限").single().sectionId)
        assertEquals(7, database.readableDatabase.version)
    }

    @Test fun scratchpadAndSnapshotPersistWithoutRecreatingDeletedLesson() = runBlocking {
        val a = store.createNotebook(NotebookKind.COURSE, "A")!!
        val b = store.createNotebook(NotebookKind.COURSE, "B")!!
        val l = store.createLesson(a.id, "lesson")!!
        assertFalse(store.updateScratchpad(b.id, l.id, "wrong"))
        assertTrue(store.updateScratchpad(a.id, l.id, "first"))
        val (q, _) = store.insertQuestion(Entry(0, l.id, EntryKind.USER, EntryAction.ASK, "q", requestContext = TurnContext(scratchpad = "first")))!!
        store.updateScratchpad(a.id, l.id, "second")
        database.close(); connect()
        assertEquals("second", store.getLesson(l.id)!!.scratchpad)
        assertEquals("first", store.getEntry(q.id)!!.requestContext.scratchpad)
        assertTrue(store.deleteLesson(l.id))
        assertFalse(store.updateScratchpad(a.id, l.id, "late"))
        assertNull(store.getLesson(l.id))
    }

    @Test fun sourceRevisionBookmarksConstraintsAndCourseIsolation() = runBlocking {
        val a = store.createNotebook(NotebookKind.COURSE, "A")!!
        val b = store.createNotebook(NotebookKind.COURSE, "B")!!
        val practice = store.createNotebook(NotebookKind.PRACTICE, "P", a.id)!!
        assertFalse(store.saveCourseDocument(practice.id, "# 极限\nwrong", null))
        assertTrue(store.saveCourseDocument(a.id, "# 极限\n假设函数连续，$$ f(x) $$，A独有条件", null))
        assertTrue(store.saveCourseDocument(b.id, "# 极限\nB私有内容", null))
        val found = store.searchCourseKnowledge(a.id, "极限怎么求")
        assertEquals(1, found.size)
        assertTrue(found.single().markdown.contains("A独有条件"))
        assertFalse(found.single().markdown.contains("B私有内容"))
        assertTrue(store.searchCourseKnowledge(practice.id, "极限").isEmpty())
        assertTrue(store.searchCourseKnowledge(NotebookStore.GENERAL_ID, "极限").isEmpty())
        assertFalse(store.isRequestContextCurrent(b.id, TurnContext(knowledge = found)))
        assertTrue(store.setCourseKnowledgeEnabled(a.id, false))
        assertTrue(store.searchCourseKnowledge(a.id, "极限").isEmpty())
        assertFalse(store.isRequestContextCurrent(a.id, TurnContext(knowledge = found)))
        assertTrue(store.setCourseKnowledgeEnabled(a.id, true))
        val sectionId = found.single().sectionId
        assertFalse(store.addCourseBookmark(b.id, sectionId))
        assertTrue(store.addCourseBookmark(a.id, sectionId))
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            database.writableDatabase.execSQL("INSERT INTO course_bookmarks(notebook_id,section_id,heading_path) VALUES(?,?,'forged')", arrayOf(b.id, sectionId))
        }
        val lesson = store.createLesson(b.id, "B")!!
        assertNull(store.insertQuestion(Entry(0, lesson.id, EntryKind.USER, EntryAction.ASK, "q", requestContext = TurnContext(knowledge = found))))
        assertTrue(store.saveCourseDocument(a.id, "# 极限\n条件已更新", 1))
        assertEquals(sectionId, store.readCourseBookmarks(a.id).single().sectionId)
        assertFalse(store.isRequestContextCurrent(a.id, TurnContext(knowledge = found)))
        assertFalse(store.saveCourseDocument(a.id, "stale editor", 1))
        assertEquals("# 极限\n条件已更新", store.getCourseDocument(a.id)!!.markdown)
        assertTrue(store.saveCourseDocument(a.id, "# 导数\n替换内容", 2))
        assertNull(store.readCourseBookmarks(a.id).single().sectionId)
        assertTrue(store.searchCourseKnowledge(a.id, "极限").isEmpty())
        assertTrue(store.deleteCourseDocument(a.id))
        assertTrue(store.readCourseSections(a.id).isEmpty())
        assertTrue(store.deleteNotebook(b.id))
        assertTrue(store.searchCourseKnowledge(b.id, "极限").isEmpty())
        database.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals(0, it.count) }
    }

    @Test fun chineseTopTwoAcceptanceAndLocalLookupTiming() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "数学")!!
        val examples = listOf(
            "极限" to "极限存在的条件是什么", "导数" to "导数的定义", "积分" to "积分计算方法", "矩阵" to "矩阵乘法",
            "向量" to "向量内积", "特征值" to "特征值如何计算", "行列式" to "行列式性质", "连续性" to "连续性如何判断",
            "概率" to "概率如何计算", "条件概率" to "条件概率的公式", "贝叶斯" to "贝叶斯定理", "期望" to "期望是什么",
            "方差" to "方差怎么求", "正态分布" to "正态分布的密度", "二项分布" to "二项分布条件", "泰勒" to "泰勒展开",
            "拉格朗日" to "拉格朗日乘数", "级数" to "级数收敛", "偏导数" to "偏导数定义", "微分方程" to "微分方程解法")
        val source = examples.joinToString("\n\n") { (topic, _) -> "# $topic\n定理前提：满足本节全部假设，结论成立。$$ f(x) = x^2 $$" }
        assertTrue(store.saveCourseDocument(n.id, source, null))
        var hits = 0
        examples.forEach { (topic, query) ->
            val result = store.searchCourseKnowledge(n.id, query)
            if (result.any { it.headingPath == topic }) hits++
            assertTrue(result.size <= 2)
            assertTrue(result.all { it.markdown.contains("定理前提") && it.markdown.contains("$$ f(x) = x^2 $$") })
        }
        assertTrue("hits=$hits", hits >= 18)
        listOf("足球比赛", "天气预报", "烧烤食谱", "旅行酒店", "电影演员").forEach { assertTrue(store.searchCourseKnowledge(n.id, it).isEmpty()) }
        val large = (1..1000).joinToString("\n\n") { "# 章节$it\n特征值条件：" + "测试定理前提与公式。".repeat(33) }
        assertTrue(store.saveCourseDocument(n.id, large, 1))
        val times = (1..20).map { val start = System.nanoTime(); store.searchCourseKnowledge(n.id, "特征值条件"); (System.nanoTime() - start) / 1_000_000 }
        val p95 = times.sorted()[18]
        android.util.Log.i("CourseFeatureTest", "Chinese hits=$hits/20; 1000 sections bytes=${large.toByteArray(Charsets.UTF_8).size}; p95=${p95}ms")
        assertTrue("p95=${p95}ms", p95 <= 200)
    }
}
