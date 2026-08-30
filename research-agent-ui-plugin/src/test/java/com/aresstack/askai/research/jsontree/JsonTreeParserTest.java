package com.aresstack.askai.research.jsontree;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The interpretation rule, case by case: only ARRAY properties are structural and recurse; object
 * properties are opaque leaves; scalars are value leaves; objects inside arrays are invisible
 * containers. Any valid JSON must project without surprises.
 */
public class JsonTreeParserTest {

    private static JsonTree parse(String json) {
        JsonTreeParseResult result = JsonTreeParser.parse(json);
        assertTrue("expected valid JSON but got: "
                + (result.isOk() ? "" : result.getDiagnostic().describeForModel()), result.isOk());
        return result.getTree();
    }

    @Test
    public void arrayPropertyBecomesAStructuralNodeAndContainerObjectsAreFlattened() {
        JsonTree tree = parse("{\"FreeRTOS\": [ {\"Tasks\": [], \"Queues\": [], "
                + "\"importance\": 0.8} ]}");
        assertEquals(1, tree.getRoots().size());
        ArrayNode freertos = (ArrayNode) tree.getRoots().get(0);
        assertEquals("FreeRTOS", freertos.getName());
        List<JsonTreeNode> children = freertos.getChildren();
        assertEquals(3, children.size());
        assertEquals("Tasks", children.get(0).getName());
        assertEquals(JsonTreeNode.Kind.ARRAY, children.get(0).getKind());
        assertEquals("Queues", children.get(1).getName());
        assertEquals(JsonTreeNode.Kind.ARRAY, children.get(1).getKind());
        ValueLeaf importance = (ValueLeaf) children.get(2);
        assertEquals("importance", importance.getName());
        assertEquals("0.8", importance.asText());
    }

    @Test
    public void anEmptyArrayIsStillAFullStructuralNode() {
        JsonTree tree = parse("{\"Tasks\": []}");
        ArrayNode tasks = (ArrayNode) tree.getRoots().get(0);
        assertEquals("Tasks", tasks.getName());
        assertTrue(tasks.getChildren().isEmpty());
        assertEquals(JsonTreeNode.Kind.ARRAY, tasks.getKind());
    }

    @Test
    public void objectPropertiesAreOpaqueLeavesAndArraysInsideThemAreNeverEntered() {
        // The acceptance example: settings is an ObjectLeaf, profiles must NOT be discoverable.
        JsonTree tree = parse("{\"settings\": {\"profiles\": []}}");
        assertEquals(1, tree.getRoots().size());
        ObjectLeaf settings = (ObjectLeaf) tree.getRoots().get(0);
        assertEquals("settings", settings.getName());
        assertEquals(JsonTreeNode.Kind.OBJECT_LEAF, settings.getKind());
        assertTrue("the opaque value keeps its content", settings.getValue().has("profiles"));
        // The structural projection proves it: no roots at all — nothing was found inside.
        assertTrue(StructuralTreeExtractor.extract(tree).isEmpty());
    }

    @Test
    public void allScalarKindsBecomeValueLeaves() {
        JsonTree tree = parse("{\"s\": \"text\", \"n\": 42, \"b\": true, \"nil\": null}");
        assertEquals(4, tree.getRoots().size());
        assertEquals("text", ((ValueLeaf) tree.getRoots().get(0)).asText());
        assertEquals("42", ((ValueLeaf) tree.getRoots().get(1)).asText());
        assertEquals("true", ((ValueLeaf) tree.getRoots().get(2)).asText());
        ValueLeaf nil = (ValueLeaf) tree.getRoots().get(3);
        assertTrue(nil.isNull());
        assertEquals("null", nil.asText());
        for (JsonTreeNode node : tree.getRoots()) {
            assertEquals(JsonTreeNode.Kind.VALUE_LEAF, node.getKind());
        }
    }

    @Test
    public void multipleArrayPropertiesOnTheSameLevelAndDeepNesting() {
        JsonTree tree = parse("{\"A\": [ {\"B\": [ {\"C\": [], \"D\": []} ], \"E\": []} ], "
                + "\"F\": []}");
        assertEquals(2, tree.getRoots().size());
        ArrayNode a = (ArrayNode) tree.getRoots().get(0);
        ArrayNode b = (ArrayNode) a.getChildren().get(0);
        assertEquals("B", b.getName());
        assertEquals("C", b.getChildren().get(0).getName());
        assertEquals("D", b.getChildren().get(1).getName());
        assertEquals("E", a.getChildren().get(1).getName());
        assertEquals("F", tree.getRoots().get(1).getName());
    }

    @Test
    public void anEmptyRootObjectYieldsAnEmptyForest() {
        assertTrue(parse("{}").getRoots().isEmpty());
    }

    @Test
    public void aRootArrayBecomesOneAnonymousArrayNode() {
        JsonTree tree = parse("[ {\"Tasks\": []}, \"loose\", 7 ]");
        assertEquals(1, tree.getRoots().size());
        ArrayNode root = (ArrayNode) tree.getRoots().get(0);
        assertNull(root.getName());
        assertTrue(root.isAnonymous());
        assertEquals(3, root.getChildren().size());
        assertEquals("Tasks", root.getChildren().get(0).getName());
        assertEquals("loose", ((ValueLeaf) root.getChildren().get(1)).asText());
        assertNull(root.getChildren().get(1).getName());
        assertEquals("7", ((ValueLeaf) root.getChildren().get(2)).asText());
    }

    @Test
    public void aRootScalarBecomesOneAnonymousValueLeaf() {
        JsonTree tree = parse("\"just a string\"");
        ValueLeaf leaf = (ValueLeaf) tree.getRoots().get(0);
        assertTrue(leaf.isAnonymous());
        assertEquals("just a string", leaf.asText());
    }

    @Test
    public void arraysNestedDirectlyInArraysBecomeAnonymousArrayNodes() {
        JsonTree tree = parse("{\"Matrix\": [ [1, 2], [] ]}");
        ArrayNode matrix = (ArrayNode) tree.getRoots().get(0);
        assertEquals(2, matrix.getChildren().size());
        ArrayNode row = (ArrayNode) matrix.getChildren().get(0);
        assertTrue(row.isAnonymous());
        assertEquals(2, row.getChildren().size());
        ArrayNode empty = (ArrayNode) matrix.getChildren().get(1);
        assertTrue(empty.isAnonymous());
        assertTrue(empty.getChildren().isEmpty());
    }

    @Test
    public void theRawDocumentSurvivesTheProjectionUnchanged() {
        String json = "{\"FreeRTOS\":[{\"Tasks\":[],\"importance\":0.8}],"
                + "\"settings\":{\"profiles\":[]}}";
        JsonTree tree = parse(json);
        assertEquals("projection must not alter the stored JSON",
                json, tree.getDocument().toString());
    }
}
