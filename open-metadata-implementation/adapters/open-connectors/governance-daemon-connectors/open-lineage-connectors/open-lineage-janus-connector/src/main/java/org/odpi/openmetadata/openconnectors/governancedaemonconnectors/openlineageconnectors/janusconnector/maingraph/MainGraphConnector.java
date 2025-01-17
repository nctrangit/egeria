/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.openmetadata.openconnectors.governancedaemonconnectors.openlineageconnectors.janusconnector.maingraph;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.IoCore;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONMapper;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONWriter;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.graphdb.tinkerpop.io.graphson.JanusGraphSONModuleV2d0;
import org.odpi.openmetadata.frameworks.connectors.properties.ConnectionProperties;
import org.odpi.openmetadata.frameworks.connectors.properties.beans.Connection;
import org.odpi.openmetadata.governanceservers.openlineage.ffdc.OpenLineageException;
import org.odpi.openmetadata.governanceservers.openlineage.ffdc.OpenLineageServerErrorCode;
import org.odpi.openmetadata.governanceservers.openlineage.maingraph.MainGraphConnectorBase;
import org.odpi.openmetadata.governanceservers.openlineage.model.*;
import org.odpi.openmetadata.governanceservers.openlineage.responses.LineageResponse;
import org.odpi.openmetadata.openconnectors.governancedaemonconnectors.openlineageconnectors.janusconnector.factory.GraphFactory;
import org.odpi.openmetadata.openconnectors.governancedaemonconnectors.openlineageconnectors.janusconnector.utils.GraphConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.*;
import static org.odpi.openmetadata.openconnectors.governancedaemonconnectors.openlineageconnectors.janusconnector.utils.GraphConstants.*;

public class MainGraphConnector extends MainGraphConnectorBase {

    private static final Logger log = LoggerFactory.getLogger(MainGraphConnector.class);
    private JanusGraph bufferGraph;
    private JanusGraph mainGraph;
    private JanusGraph historyGraph;
    private JanusGraph mockGraph;

    /**
     * Initialize the connector.
     *
     * @param connectorInstanceId  - unique id for the connector instance - useful for messages etc
     * @param connectionProperties - POJO for the configuration used to create the connector.
     */
    @Override
    public void initialize(String connectorInstanceId, ConnectionProperties connectionProperties) {
        super.initialize(connectorInstanceId, connectionProperties);
    }

    public void initializeGraphDB(){
        String graphDB = connectionProperties.getConfigurationProperties().get("graphDB").toString();
        GraphFactory graphFactory = new GraphFactory();
        this.mainGraph = graphFactory.openGraph(graphDB,connectionProperties);
    }

    /**
     * Returns a lineage subgraph.
     *
     * @param graphName main, buffer, mock, history.
     * @param scope     source-and-destination, end-to-end, ultimate-source, ultimate-destination, glossary.
     * @param view      The view queried by the user: hostview, tableview, columnview.
     * @param guid      The guid of the node of which the lineage is queried from.
     * @return A subgraph containing all relevant paths, in graphSON format.
     */
    public LineageResponse lineage(GraphName graphName, Scope scope, View view, String guid) throws OpenLineageException {
        String methodName = "MainGraphConnector.lineage";
        Graph graph = getJanusGraph(graphName);
        GraphTraversalSource g = graph.traversal();
        try {
            g.V().has(PROPERTY_KEY_ENTITY_NODE_ID, guid).next();
        } catch (NoSuchElementException e) {
            OpenLineageServerErrorCode errorCode = OpenLineageServerErrorCode.NODE_NOT_FOUND;
            throw new OpenLineageException(errorCode.getHTTPErrorCode(),
                    this.getClass().getName(),
                    methodName,
                    errorCode.getFormattedErrorMessage(),
                    errorCode.getSystemAction(),
                    errorCode.getUserAction());
        }
        String edgeLabel = getEdgeLabel(view);
        if (scope != null) {
            switch (scope) {
                case SOURCE_AND_DESTINATION:
                    return sourceAndDestination(graph, edgeLabel, guid);
                case END_TO_END:
                    return endToEnd(graph, edgeLabel, guid);
                case ULTIMATE_SOURCE:
                    return ultimateSource(graph, edgeLabel, guid);
                case ULTIMATE_DESTINATION:
                    return ultimateDestination(graph, edgeLabel, guid);
                case GLOSSARY:
                    return glossary(graph, guid);
            }
        }
        OpenLineageServerErrorCode errorCode = OpenLineageServerErrorCode.INVALID_SCOPE;
        throw new OpenLineageException(errorCode.getHTTPErrorCode(),
                this.getClass().getName(),
                methodName,
                errorCode.getFormattedErrorMessage(),
                errorCode.getSystemAction(),
                errorCode.getUserAction());
    }

    /**
     * Returns a subgraph containing all paths leading from any root node to the queried node, and all of the paths
     * leading from the queried node to any leaf nodes. The queried node can be a column or table.
     *
     * @param graph     MAIN, BUFFER, MOCK, HISTORY.
     * @param edgeLabel The view queried by the user: tableview, columnview.
     * @param guid      The guid of the node of which the lineage is queried of. This can be a column or a table.
     * @return a subgraph in the GraphSON format.
     */
     LineageResponse endToEnd(Graph graph, String edgeLabel, String guid) {
        GraphTraversalSource g = graph.traversal();

        Graph endToEndGraph = (Graph)
                g.V().has(PROPERTY_KEY_ENTITY_NODE_ID, guid).
                        union(
                                until(inE(edgeLabel).count().is(0)).
                                        repeat((Traversal) inE(edgeLabel).subgraph("subGraph").outV().simplePath()),
                                until(outE(edgeLabel).count().is(0)).
                                        repeat((Traversal) outE(edgeLabel).subgraph("subGraph").inV().simplePath())
                        ).cap("subGraph").next();

        LineageResponse lineageResponse = getLineageResponse(endToEndGraph);
        return lineageResponse;
    }

    private LineageEdge abstractEdge(Edge originalEdge) {
        String sourceNodeID = originalEdge.outVertex().property(PROPERTY_KEY_ENTITY_NODE_ID).value().toString();
        String destinationNodeId = originalEdge.inVertex().property(PROPERTY_KEY_ENTITY_NODE_ID).value().toString();
        LineageEdge lineageEdge = new LineageEdge(originalEdge.label(), sourceNodeID, destinationNodeId);
        return lineageEdge;
    }

    private LineageVertex abstractVertex(Vertex originalVertex) {
        String nodeID = originalVertex.property(PROPERTY_KEY_ENTITY_NODE_ID).value().toString();
        String nodeType = originalVertex.label();
        LineageVertex lineageVertex = new LineageVertex(nodeID, nodeType);

        if (originalVertex.property(PROPERTY_KEY_DISPLAY_NAME).isPresent()) {
            String displayName = originalVertex.property(PROPERTY_KEY_DISPLAY_NAME).value().toString();
            lineageVertex.setDisplayName(displayName);
        }

        //Displayname key is stored inconsistently in the graphDB.
        else if (originalVertex.property(PROPERTY_KEY_ALTERNATIVE_DISPLAY_NAME).isPresent()) {
            String displayName = originalVertex.property(PROPERTY_KEY_ALTERNATIVE_DISPLAY_NAME).value().toString();
            lineageVertex.setDisplayName(displayName);
        }

        if (originalVertex.property(PROPERTY_KEY_ENTITY_GUID).isPresent()) {
            String guid = originalVertex.property(PROPERTY_KEY_ENTITY_GUID).value().toString();
            lineageVertex.setGuid(guid);
        }
        Map<String, String> attributes = retrieveProperties(originalVertex);
        lineageVertex.setAttributes(attributes);
        return lineageVertex;
    }

    /**
     *  Retrieve all properties from the db and return the ones that match the whitelist. This will filter out irrelevant
     *  properties that should not be returned to a UI.
     * @param originalVertex
     * @return
     */
    private Map<String, String> retrieveProperties(Vertex originalVertex) {
        Map<String, String> attributes = new HashMap<>();
        Iterator originalProperties = originalVertex.properties();
        while(originalProperties.hasNext()){
            Property originalProperty = (Property) originalProperties.next();
            if(returnedPropertiesWhiteList.contains(originalProperty.key()))
                attributes.put(originalProperty.key(), originalProperty.value().toString());
        }
        return attributes;
    }

    /**
     * Returns a subgraph containing all root of the full graph that are connected with the queried node.
     * The queried node can be a column or table.
     *
     * @param graph     MAIN, BUFFER, MOCK, HISTORY.
     * @param edgeLabel The view queried by the user: tableview, columnview.
     * @param guid      The guid of the node of which the lineage is queried of. This can be a column or a table.
     * @return a subgraph in the GraphSON format.
     */
     LineageResponse ultimateSource(Graph graph, String edgeLabel, String guid) throws OpenLineageException {
        String methodName = "MainGraphConnector.ultimateSource";
        GraphTraversalSource g = graph.traversal();

        List<Vertex> sourcesList = g.V().has(GraphConstants.PROPERTY_KEY_ENTITY_NODE_ID, guid).
                until(inE(edgeLabel).count().is(0)).
                repeat(inE(edgeLabel).outV().simplePath()).
                dedup().toList();

        detectProblematicCycle(methodName, sourcesList);

        Vertex originalQueriedVertex = g.V().has(GraphConstants.PROPERTY_KEY_ENTITY_NODE_ID, guid).next();

        List<LineageVertex> lineageVertices = new ArrayList<>();
        List<LineageEdge> lineageEdges = new ArrayList<>();

        LineageVertex queriedVertex = abstractVertex(originalQueriedVertex);
        lineageVertices.add(queriedVertex);

        addSourceCondensation(sourcesList, lineageVertices, lineageEdges, originalQueriedVertex, queriedVertex);
        LineageVerticesAndEdges lineageVerticesAndEdges = new LineageVerticesAndEdges(lineageVertices, lineageEdges);
        LineageResponse lineageResponse = new LineageResponse(lineageVerticesAndEdges);
        return lineageResponse;
    }

    private void detectProblematicCycle(String methodName, List<Vertex> vertexList) throws OpenLineageException {
        if (!vertexList.isEmpty())
            return;
        OpenLineageServerErrorCode errorCode = OpenLineageServerErrorCode.LINEAGE_CYCLE;
        throw new OpenLineageException(errorCode.getHTTPErrorCode(),
                this.getClass().getName(),
                methodName,
                errorCode.getFormattedErrorMessage(),
                errorCode.getSystemAction(),
                errorCode.getUserAction());
    }


    /**
     * Returns a subgraph containing all leaf nodes of the full graph that are connected with the queried node.
     * The queried node can be a column or table.
     *
     * @param graph     MAIN, BUFFER, MOCK, HISTORY.
     * @param edgeLabel The view queried by the user: tableview, columnview.
     * @param guid      The guid of the node of which the lineage is queried of. This can be a column or table node.
     * @return a subgraph in the GraphSON format.
     */
     LineageResponse ultimateDestination(Graph graph, String edgeLabel, String guid) throws OpenLineageException {
        String methodName = "MainGraphConnector.ultimateDestination";
        GraphTraversalSource g = graph.traversal();

        List<Vertex> destinationsList = g.V().has(GraphConstants.PROPERTY_KEY_ENTITY_NODE_ID, guid).until(outE(edgeLabel).count().is(0)).
                repeat(outE(edgeLabel).inV().simplePath()).
                dedup().toList();

        detectProblematicCycle(methodName, destinationsList);

        Vertex originalQueriedVertex = g.V().has(GraphConstants.PROPERTY_KEY_ENTITY_NODE_ID, guid).next();
        LineageVertex queriedVertex = abstractVertex(originalQueriedVertex);

        List<LineageVertex> lineageVertices = new ArrayList<>();
        List<LineageEdge> lineageEdges = new ArrayList<>();

        lineageVertices.add(queriedVertex);

        addDestinationCondensation(destinationsList, lineageVertices, lineageEdges, originalQueriedVertex, queriedVertex);
        LineageVerticesAndEdges lineageVerticesAndEdges = new LineageVerticesAndEdges(lineageVertices, lineageEdges);
        LineageResponse lineageResponse = new LineageResponse(lineageVerticesAndEdges);
        return lineageResponse;
    }

    /**
     * Returns a subgraph containing all root and leaf nodes of the full graph that are connected with the queried node.
     * The queried node can be a column or table.
     *
     * @param graph     MAIN, BUFFER, MOCK, HISTORY.
     * @param edgeLabel The view queried by the user: tableview, columnview.
     * @param guid      The guid of the node of which the lineage is queried of. This can be a column or a table.
     * @return a subgraph in the GraphSON format.
     */
     LineageResponse sourceAndDestination(Graph graph, String edgeLabel, String guid) throws OpenLineageException {
        String methodName = "MainGraphConnector.sourceAndDestination";
        GraphTraversalSource g = graph.traversal();

        List<Vertex> sourcesList = g.V().has(GraphConstants.PROPERTY_KEY_ENTITY_NODE_ID, guid).
                until(inE(edgeLabel).count().is(0)).
                repeat(inE(edgeLabel).outV().simplePath()).
                dedup().toList();

        List<Vertex> destinationsList = g.V().has(GraphConstants.PROPERTY_KEY_ENTITY_NODE_ID, guid).
                until(outE(edgeLabel).count().is(0)).
                repeat(outE(edgeLabel).inV().simplePath()).
                dedup().toList();

        detectProblematicCycle(methodName, sourcesList);
        detectProblematicCycle(methodName, destinationsList);


        Vertex originalQueriedVertex = g.V().has(GraphConstants.PROPERTY_KEY_ENTITY_NODE_ID, guid).next();
        LineageVertex queriedVertex = abstractVertex(originalQueriedVertex);

        List<LineageVertex> lineageVertices = new ArrayList<>();
        List<LineageEdge> lineageEdges = new ArrayList<>();
        lineageVertices.add(queriedVertex);
        addSourceCondensation(sourcesList, lineageVertices, lineageEdges, originalQueriedVertex, queriedVertex);

        addDestinationCondensation(destinationsList, lineageVertices, lineageEdges, originalQueriedVertex, queriedVertex);

        LineageVerticesAndEdges lineageVerticesAndEdges = new LineageVerticesAndEdges(lineageVertices, lineageEdges);
        LineageResponse lineageResponse = new LineageResponse(lineageVerticesAndEdges);
        return lineageResponse;
    }

    private void addSourceCondensation(List<Vertex> sourcesList,
                                       List<LineageVertex> lineageVertices,
                                       List<LineageEdge> lineageEdges,
                                       Vertex originalQueriedVertex,
                                       LineageVertex queriedVertex) {
        //Only add condensed node if there is something to condense in the first place. The gremlin query returns the queried node
        //when there isn't any.
        if (sourcesList.get(0).property(PROPERTY_KEY_ENTITY_NODE_ID).equals(originalQueriedVertex.property(PROPERTY_KEY_ENTITY_NODE_ID)))
            return;
        LineageVertex condensedVertex = new LineageVertex(PROPERTY_VALUE_NODE_ID_CONDENSED_SOURCE, NODE_LABEL_CONDENSED);
        lineageVertices.add(condensedVertex);

        for (Vertex originalVertex : sourcesList) {
            LineageVertex newVertex = abstractVertex(originalVertex);
            LineageEdge newEdge = new LineageEdge(
                    EDGE_LABEL_CONDENSED,
                    newVertex.getNodeID(),
                    condensedVertex.getNodeID()
            );
                lineageVertices.add(newVertex);
                lineageEdges.add(newEdge);
        }
        LineageEdge sourceEdge = new LineageEdge(
                EDGE_LABEL_CONDENSED,
                condensedVertex.getNodeID(),
                queriedVertex.getNodeID()
        );
        lineageEdges.add(sourceEdge);
    }

    private void addDestinationCondensation
            (List<Vertex> destinationsList, List<LineageVertex> lineageVertices, List<LineageEdge> lineageEdges, Vertex
                    originalQueriedVertex, LineageVertex queriedVertex) {
        //Only add condensed node if there is something to condense in the first place. The gremlin query returns the queried node
        //when there isn't any.
        if (!destinationsList.get(0).property(PROPERTY_KEY_ENTITY_NODE_ID).equals(originalQueriedVertex.property(PROPERTY_KEY_ENTITY_NODE_ID))) {
            LineageVertex condensedDestinationVertex = new LineageVertex(PROPERTY_VALUE_NODE_ID_CONDENSED_DESTINATION, NODE_LABEL_CONDENSED);
            for (Vertex originalVertex : destinationsList) {
                LineageVertex newVertex = abstractVertex(originalVertex);
                LineageEdge newEdge = new LineageEdge(
                        EDGE_LABEL_CONDENSED,
                        condensedDestinationVertex.getNodeID(),
                        newVertex.getNodeID()
                );
                if (newVertex != null)
                    lineageVertices.add(newVertex);
                if (newEdge != null)
                    lineageEdges.add(newEdge);
            }
            LineageEdge destinationEdge = new LineageEdge(
                    EDGE_LABEL_CONDENSED,
                    queriedVertex.getNodeID(),
                    condensedDestinationVertex.getNodeID()
            );
            lineageVertices.add(condensedDestinationVertex);
            lineageEdges.add(destinationEdge);
        }
    }


    /**
     * Returns a subgraph containing all columns or tables connected to the queried glossary term, as well as all
     * columns or tables connected to synonyms of the queried glossary term.
     *
     * @param graph MAIN, BUFFER, MOCK, HISTORY.
     * @param guid  The guid of the glossary term of which the lineage is queried of.
     * @return a subgraph in the GraphSON format.
     */
     LineageResponse glossary(Graph graph, String guid) {
        GraphTraversalSource g = graph.traversal();

        Graph subGraph = (Graph)
                g.V().has(GraphConstants.PROPERTY_KEY_ENTITY_NODE_ID, guid)
                        .emit().
                        repeat(bothE(EDGE_LABEL_GLOSSARYTERM_TO_GLOSSARYTERM).subgraph("subGraph").simplePath().otherV())
                        .inE(EDGE_LABEL_SEMANTIC).subgraph("subGraph").outV()
                        .cap("subGraph").next();

        LineageResponse lineageResponse = getLineageResponse(subGraph);
        return lineageResponse;
    }

    private LineageResponse getLineageResponse(Graph subGraph) {
        Iterator<Vertex> originalVertices = subGraph.vertices();
        Iterator<Edge> originalEdges = subGraph.edges();

        List<LineageVertex> lineageVertices = new ArrayList<>();
        List<LineageEdge> lineageEdges = new ArrayList<>();

        while (originalVertices.hasNext()) {
            LineageVertex newVertex = abstractVertex(originalVertices.next());
            if (newVertex != null) {
                lineageVertices.add(newVertex);
            }
        }
        while (originalEdges.hasNext()) {
            LineageEdge newLineageEdge = abstractEdge(originalEdges.next());
            if (newLineageEdge != null) {
                lineageEdges.add(newLineageEdge);
            }
        }
        LineageVerticesAndEdges lineageVerticesAndEdges = new LineageVerticesAndEdges(lineageVertices, lineageEdges);
        LineageResponse lineageResponse = new LineageResponse(lineageVerticesAndEdges);
        return lineageResponse;
    }

    /**
     * Retrieve the label of the edges that are to be traversed with the gremlin query.
     *
     * @param view The view queried by the user: table-view, column-view.
     * @return The label of the edges that are to be traversed with the gremlin query.
     */
    private String getEdgeLabel(View view) throws OpenLineageException {
        String methodName = "MainGraphConnector.getEdgeLabel";
        String edgeLabel = "";
        if (view != null) {
            switch (view) {
                case TABLE_VIEW:
                    edgeLabel = EDGE_LABEL_TABLE_AND_PROCESS;
                    break;
                case COLUMN_VIEW:
                    edgeLabel = EDGE_LABEL_COLUMN_AND_PROCESS;
                    break;
            }
            return edgeLabel;
        }
        OpenLineageServerErrorCode errorCode = OpenLineageServerErrorCode.INVALID_VIEW;
        throw new OpenLineageException(errorCode.getHTTPErrorCode(),
                this.getClass().getName(),
                methodName,
                errorCode.getFormattedErrorMessage(),
                errorCode.getSystemAction(),
                errorCode.getUserAction());
    }


    /**
     * Write an entire graph to disc in the Egeria root folder, in the .GraphMl format.
     *
     * @param graphName MAIN, BUFFER, MOCK, HISTORY.
     */
    public void dumpGraph(GraphName graphName) throws OpenLineageException {
        JanusGraph graph = getJanusGraph(graphName);
        try {
            graph.io(IoCore.graphml()).writeGraph("graph-" + graphName + ".graphml");
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    /**
     * Return an entire graph, in GraphSon format.
     *
     * @param graphName MAIN, BUFFER, MOCK, HISTORY.
     * @return The queried graph, in graphSON format.
     */
    public String exportGraph(GraphName graphName) throws OpenLineageException {
        JanusGraph graph = getJanusGraph(graphName);
        return janusGraphToGraphson(graph);
    }

    /**
     * Convert a Graph object which is originally created by a Janusgraph writer to a String in GraphSON format.
     *
     * @param graph The Graph object to be converted.
     * @return The provided Graph as a String, in the GraphSON format.
     */
    private String janusGraphToGraphson(Graph graph) {
        OutputStream out = new ByteArrayOutputStream();
        GraphSONMapper mapper = GraphSONMapper.build().addCustomModule(JanusGraphSONModuleV2d0.getInstance()).create();
        GraphSONWriter writer = GraphSONWriter.build().mapper(mapper).wrapAdjacencyList(true).create();

        try {
            writer.writeGraph(out, graph);
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        return out.toString();
    }

    /**
     * Retrieve an Open Lineage Services graph.
     *
     * @param graphName The name of the queried graph.
     * @return The Graph object.
     */
    private JanusGraph getJanusGraph(GraphName graphName) throws OpenLineageException {
        String methodName = "MainGraphConnector.getJanusGraph";
        JanusGraph graph = null;
        if (graphName != null) {
            switch (graphName) {
                case MAIN:
                    graph = mainGraph;
                    break;
                case BUFFER:
                    graph = bufferGraph;
                    break;
                case HISTORY:
                    graph = historyGraph;
                    break;
                case MOCK:
                    graph = mockGraph;
                    break;
            }
            return graph;
        }
        OpenLineageServerErrorCode errorCode = OpenLineageServerErrorCode.INVALID_SOURCE;
        throw new OpenLineageException(errorCode.getHTTPErrorCode(),
                this.getClass().getName(),
                methodName,
                errorCode.getFormattedErrorMessage(),
                errorCode.getSystemAction(),
                errorCode.getUserAction());
    }

    public Object getMainGraph() {
        return mainGraph;
    }


}
