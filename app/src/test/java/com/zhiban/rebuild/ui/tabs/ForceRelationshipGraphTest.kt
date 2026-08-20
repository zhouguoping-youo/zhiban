package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.geometry.Offset
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.interaction.ContactInteractionIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceRelationshipGraphTest {
    @Test
    fun `model keeps company as relationship evidence instead of a peer node`() {
        val people = mapOf(
            RelationshipPersonIds.SELF to RelationshipPersonUi(RelationshipPersonIds.SELF, "我", true),
            "li" to RelationshipPersonUi("li", "李应啸", false, company = "示例科技有限公司", title = "产品经理"),
            "ding" to RelationshipPersonUi("ding", "丁波", false, company = " 示例科技有限公司 ", title = "研发经理"),
        )
        val edges = listOf(
            edge("self-li", RelationshipPersonIds.SELF, "li", "COLLEAGUE", 1.0, true),
            edge("li-ding", "li", "ding", "COLLEAGUE", 0.86, false),
        )

        val model = buildForceGraphModel(RelationshipPersonIds.SELF, people, edges)

        assertEquals(3, model.nodes.size)
        assertEquals(ForceGraphNodeKind.FOCUS, model.nodes.first { it.id == RelationshipPersonIds.SELF }.kind)
        assertEquals(ForceGraphNodeKind.WORK, model.nodes.first { it.id == "li" }.kind)
        assertEquals("示例科技有限公司", model.links.first { it.fromId == "li" && it.toId == "ding" }.evidenceLabel)
    }

    @Test
    fun `seed places requested focus at viewport center`() {
        val bodies = seedForceBodies(
            nodeIds = listOf("a", "focus", "b"),
            rootId = "focus",
            width = 320f,
            height = 480f,
        )

        assertEquals(Offset(160f, 240f), bodies.getValue("focus").position)
        assertTrue(bodies.getValue("a").position != bodies.getValue("b").position)
    }

    @Test
    fun `model keeps owner as centered focus when relationships only connect contacts`() {
        val people = mapOf(
            RelationshipPersonIds.SELF to RelationshipPersonUi(RelationshipPersonIds.SELF, "我", true),
            "a" to RelationshipPersonUi("a", "联系人甲", false),
            "b" to RelationshipPersonUi("b", "联系人乙", false),
        )

        val model = buildForceGraphModel(
            RelationshipPersonIds.SELF,
            people,
            listOf(edge("a-b", "a", "b", "COLLEAGUE", 0.9, false)),
        )

        assertEquals(setOf(RelationshipPersonIds.SELF, "a", "b"), model.nodes.map { it.id }.toSet())
        assertEquals(ForceGraphNodeKind.FOCUS, model.nodes.first { it.id == RelationshipPersonIds.SELF }.kind)
        assertTrue(model.links.none { it.fromId == RelationshipPersonIds.SELF || it.toId == RelationshipPersonIds.SELF })
    }

    @Test
    fun `simulation combines repulsion spring centering and damping`() {
        val bodies = mutableMapOf(
            "focus" to ForceBody(Offset(40f, 160f)),
            "other" to ForceBody(Offset(560f, 160f)),
        )
        val link = ForceGraphLink("focus", "other", "COLLEAGUE", "同事", 1f, false)

        advanceForceSimulation(
            bodies = bodies,
            links = listOf(link),
            rootId = "focus",
            width = 600f,
            height = 320f,
            density = 1f,
            timeScale = 1f,
        )

        assertTrue(bodies.getValue("focus").position.x > 40f)
        assertTrue(bodies.getValue("other").position.x < 560f)
        assertTrue(bodies.values.all { it.velocity.getDistance() > 0f })
    }

    @Test
    fun `historical link keeps its state and displays the concrete past relationship`() {
        val people = mapOf(
            RelationshipPersonIds.SELF to RelationshipPersonUi(RelationshipPersonIds.SELF, "我", true),
            "former" to RelationshipPersonUi("former", "前同事", false),
        )
        val historical = edge(
            "history",
            RelationshipPersonIds.SELF,
            "former",
            "COLLEAGUE",
            1.0,
            true,
        ).copy(status = "HISTORICAL")

        val model = buildForceGraphModel(RelationshipPersonIds.SELF, people, listOf(historical))

        assertTrue(model.links.single().isHistorical)
        assertEquals("前同事", graphRelationLabel("COLLEAGUE", isHistorical = true))
    }

    @Test
    fun `ego projection keeps two hops and excludes the third hop`() {
        val edges = listOf(
            edge("root-a", RelationshipPersonIds.SELF, "a", "COLLEAGUE", 1.0, true),
            edge("a-b", "a", "b", "COLLEAGUE", 1.0, true),
            edge("b-c", "b", "c", "COLLEAGUE", 1.0, true),
            edge("c-d", "c", "d", "COLLEAGUE", 1.0, true),
        )

        val projection = projectRelationshipGraph(
            rootId = "a",
            peopleIds = setOf(RelationshipPersonIds.SELF, "a", "b", "c", "d"),
            edges = edges,
        )

        assertEquals(mapOf("a" to 0, RelationshipPersonIds.SELF to 1, "b" to 1, "c" to 2), projection.hopByNode)
        assertEquals(setOf("root-a", "a-b", "b-c"), projection.edges.map { it.edgeId }.toSet())
        assertEquals(0.40f, relationshipGraphPresentation(projection).getValue("c").opacity)
        assertTrue(relationshipGraphPresentation(projection).getValue("c").showLabel.not())
        assertEquals(0.15f, relationshipGraphPresentation(projection).getValue(RelationshipPersonIds.SELF).opacity)
        assertTrue(relationshipGraphPresentation(projection).getValue(RelationshipPersonIds.SELF).showLabel.not())
    }

    @Test
    fun `root projection retains every connected edge`() {
        val edges = listOf(
            edge("a-b", "a", "b", "COLLEAGUE", 1.0, true),
            edge("b-c", "b", "c", "FRIEND", 1.0, true),
        )

        val projection = projectRelationshipGraph(
            rootId = RelationshipPersonIds.SELF,
            peopleIds = setOf(RelationshipPersonIds.SELF, "a", "b", "c"),
            edges = edges,
        )

        assertEquals(edges, projection.edges)
        assertEquals(setOf("a", "b", "c"), projection.hopByNode.keys - RelationshipPersonIds.SELF)
        assertTrue(projection.isEgoView.not())
    }

    @Test
    fun `interaction intensity maps contacts to Dunbar rings`() {
        assertEquals(RelationshipGraphRing.INNER, relationshipGraphRing(8))
        assertEquals(RelationshipGraphRing.MIDDLE, relationshipGraphRing(3))
        assertEquals(RelationshipGraphRing.OUTER, relationshipGraphRing(1))
        assertEquals(RelationshipGraphRing.UNKNOWN, relationshipGraphRing(0))
    }

    @Test
    fun `projection carries interaction ring without changing persisted edges`() {
        val projection = projectRelationshipGraph(
            rootId = RelationshipPersonIds.SELF,
            peopleIds = setOf(RelationshipPersonIds.SELF, "a"),
            edges = listOf(edge("self-a", RelationshipPersonIds.SELF, "a", "COLLEAGUE", 1.0, true)),
            interactionIntensity = listOf(ContactInteractionIntensity("a", 9, 100L)),
        )

        assertEquals(RelationshipGraphRing.INNER, projection.ringByNode.getValue("a"))
        assertEquals(1, projection.edges.size)
    }

    private fun edge(id: String, from: String, to: String, type: String, confidence: Double, confirmed: Boolean) = RelationshipEdgeEntity(
        edgeId = id,
        fromContactId = from,
        toContactId = to,
        relationType = type,
        evidenceDigest = "test",
        evidenceRefsJson = "[]",
        confidence = confidence,
        userConfirmed = confirmed,
        skillId = null,
        status = "ACTIVE",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
