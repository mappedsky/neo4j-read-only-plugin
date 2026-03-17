package com.mappedsky.neo4j;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.event.LabelEntry;
import org.neo4j.graphdb.event.PropertyEntry;
import org.neo4j.graphdb.event.TransactionData;

import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Minimal stub of {@link TransactionData} for use in unit tests.
 */
class MockTransactionData implements TransactionData {

    private final String username;
    private final boolean hasWrites;

    private MockTransactionData(String username, boolean hasWrites) {
        this.username = username;
        this.hasWrites = hasWrites;
    }

    static MockTransactionData withWrites(String username) {
        return new MockTransactionData(username, true);
    }

    static MockTransactionData readOnly(String username) {
        return new MockTransactionData(username, false);
    }

    @Override
    public String username() {
        return username;
    }

    @Override
    public Map<String, Object> metaData() {
        return Collections.emptyMap();
    }

    @Override
    public Iterable<Node> createdNodes() {
        return hasWrites ? Collections.singletonList(stubNode()) : Collections.emptyList();
    }

    @Override public Iterable<Node> deletedNodes()                                              { return Collections.emptyList(); }
    @Override public Iterable<Relationship> createdRelationships()                              { return Collections.emptyList(); }
    @Override public Iterable<Relationship> deletedRelationships()                              { return Collections.emptyList(); }
    @Override public Iterable<PropertyEntry<Node>> assignedNodeProperties()                     { return Collections.emptyList(); }
    @Override public Iterable<PropertyEntry<Node>> removedNodeProperties()                      { return Collections.emptyList(); }
    @Override public Iterable<PropertyEntry<Relationship>> assignedRelationshipProperties()     { return Collections.emptyList(); }
    @Override public Iterable<PropertyEntry<Relationship>> removedRelationshipProperties()      { return Collections.emptyList(); }
    @Override public Iterable<LabelEntry> assignedLabels()                                      { return Collections.emptyList(); }
    @Override public Iterable<LabelEntry> removedLabels()                                       { return Collections.emptyList(); }
    @Override public boolean isDeleted(Node node)                                               { return false; }
    @Override public boolean isDeleted(Relationship relationship)                               { return false; }

    // -------------------------------------------------------------------------

    private static Node stubNode() {
        return new StubNode();
    }

    /** Empty {@link ResourceIterable} helper used by {@link StubNode}. */
    private static <T> ResourceIterable<T> emptyResourceIterable() {
        return new ResourceIterable<>() {
            @Override
            public ResourceIterator<T> iterator() {
                return new ResourceIterator<>() {
                    @Override public boolean hasNext() { return false; }
                    @Override public T next()          { throw new NoSuchElementException(); }
                    @Override public void close()      {}
                };
            }
            @Override
            public void close() {}
        };
    }

    private static class StubNode implements Node {
        @Override public long getId()          { return 0; }
        @Override public String getElementId() { return "stub-0"; }

        @Override public ResourceIterable<Relationship> getRelationships()                                              { return emptyResourceIterable(); }
        @Override public ResourceIterable<Relationship> getRelationships(RelationshipType... types)                     { return emptyResourceIterable(); }
        @Override public ResourceIterable<Relationship> getRelationships(Direction dir, RelationshipType... types)      { return emptyResourceIterable(); }
        @Override public ResourceIterable<Relationship> getRelationships(Direction dir)                                 { return emptyResourceIterable(); }
        @Override public boolean hasRelationship()                                                                      { return false; }
        @Override public boolean hasRelationship(RelationshipType... types)                                             { return false; }
        @Override public boolean hasRelationship(Direction dir, RelationshipType... types)                              { return false; }
        @Override public boolean hasRelationship(Direction dir)                                                         { return false; }
        @Override public Relationship getSingleRelationship(RelationshipType type, Direction dir)                       { return null; }
        @Override public Relationship createRelationshipTo(Node other, RelationshipType type)                           { throw new UnsupportedOperationException(); }
        @Override public Iterable<RelationshipType> getRelationshipTypes()                                              { return Collections.emptyList(); }
        @Override public int getDegree()                                                                                { return 0; }
        @Override public int getDegree(RelationshipType type)                                                           { return 0; }
        @Override public int getDegree(Direction dir)                                                                   { return 0; }
        @Override public int getDegree(RelationshipType type, Direction dir)                                            { return 0; }
        @Override public void addLabel(Label label)                                                                     {}
        @Override public void removeLabel(Label label)                                                                  {}
        @Override public boolean hasLabel(Label label)                                                                  { return false; }
        @Override public Iterable<Label> getLabels()                                                                    { return Collections.emptyList(); }
        @Override public boolean hasProperty(String key)                                                                { return false; }
        @Override public Object getProperty(String key)                                                                 { throw new UnsupportedOperationException(); }
        @Override public Object getProperty(String key, Object defaultValue)                                            { return defaultValue; }
        @Override public void setProperty(String key, Object value)                                                     { throw new UnsupportedOperationException(); }
        @Override public Object removeProperty(String key)                                                              { throw new UnsupportedOperationException(); }
        @Override public Iterable<String> getPropertyKeys()                                                             { return Collections.emptyList(); }
        @Override public Map<String, Object> getProperties(String... keys)                                              { return Collections.emptyMap(); }
        @Override public Map<String, Object> getAllProperties()                                                          { return Collections.emptyMap(); }
        @Override public void delete()                                                                                  { throw new UnsupportedOperationException(); }
    }
}
