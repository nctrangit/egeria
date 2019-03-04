/* SPDX-License-Identifier: Apache 2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.openmetadata.governanceservers.openlineage.connectors;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONWriter;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.EntityProxy;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.InstancePropertyValue;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.PrimitivePropertyValue;
import org.odpi.openmetadata.repositoryservices.events.OMRSInstanceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public class GremlinConnector {


    private static final Logger log = LoggerFactory.getLogger(GremlinConnector.class);
    private Graph graph;
    private GraphTraversalSource g;

    public GremlinConnector() {
        this.graph = TinkerGraph.open();
        this.g = graph.traversal();
    }

    public void addNewEntity(OMRSInstanceEvent omrsInstanceEvent) {
        InstancePropertyValue instancePropertyValue = omrsInstanceEvent.getEntity().getProperties().getInstanceProperties().get("qualifiedName");
        PrimitivePropertyValue primitivePropertyValue = (PrimitivePropertyValue) instancePropertyValue;
        String qualifiedName = primitivePropertyValue.getPrimitiveValue().toString();

        String GUID = omrsInstanceEvent.getEntity().getGUID();

        Vertex v1 = g.addV(GUID).next();
        v1.property("qualifiedName", qualifiedName);
        v1 = g.V().hasLabel(GUID).next();

    }

    public void addNewRelationship(OMRSInstanceEvent omrsInstanceEvent) {
        EntityProxy proxy1 = omrsInstanceEvent.getRelationship().getEntityOneProxy();
        EntityProxy proxy2 = omrsInstanceEvent.getRelationship().getEntityTwoProxy();

        String GUID1 = proxy1.getGUID();
        String GUID2 = proxy2.getGUID();

        Vertex v1 = g.V().hasLabel(GUID1).next();
        Vertex v2 = g.V().hasLabel(GUID2).next();
        v1.addEdge("Semantic Relationship", v2);

        File file = new File("lineageGraph.txt");

        FileOutputStream fos = null;

        try {
            fos = new FileOutputStream(file, true);
            try {
                GraphSONWriter.build().create().writeGraph(fos, graph);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }
}