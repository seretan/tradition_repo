package net.stemmaweb.rest;

import com.qmino.miredot.annotations.ReturnType;
import net.stemmaweb.model.AnnotationLabelModel;
import net.stemmaweb.services.DatabaseService;
import net.stemmaweb.services.GraphDatabaseServiceProvider;
import org.neo4j.graphdb.*;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static net.stemmaweb.rest.Util.jsonerror;

public class AnnotationLabel {
    private GraphDatabaseService db;
    private String tradId;
    private String name;

    public AnnotationLabel(String tradId, String name) {
        GraphDatabaseServiceProvider dbServiceProvider = new GraphDatabaseServiceProvider();
        db = dbServiceProvider.getDatabase();
        this.tradId = tradId;
        this.name = name;
    }

    /**
     * Gets the information for the given annotation type name.
     *
     * @summary Get annotation label spec
     *
     * @return A JSON AnnotationLabelModel or a JSON error message
     * @statuscode 200 on success
     * @statuscode 400 if there is an error in the annotation type specification
     * @statuscode 500 on failure, with an error report in JSON format
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    @ReturnType(clazz = AnnotationLabelModel.class)
    public Response getAnnotationLabel() {
        Node ourNode = lookupAnnotationLabel();
        if (ourNode == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(new AnnotationLabelModel(ourNode)).build();
    }

    /**
     * Creates or updates an annotation type specification
     *
     * @summary Put annotation label spec
     * @param alm - The AnnotationLabelModel specification to use
     * @return The AnnotationLabelModel specification created / updated
     * @statuscode 200 on update of existing label
     * @statuscode 201 on creation of new label
     * @statuscode 400 if there is an error in the annotation type specification
     * @statuscode 500 on failure, with an error report in JSON format
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    @ReturnType(clazz = AnnotationLabelModel.class)
    public Response createOrUpdateAnnotationLabel(AnnotationLabelModel alm) {
        Node ourNode = lookupAnnotationLabel();
        Node tradNode = DatabaseService.getTraditionNode(tradId, db);
        boolean isNew = false;
        try (Transaction tx = db.beginTx()) {
            // Get the existing list of annotation labels associated with this tradition
            List<String> existingLabels = getExistingLabelsForTradition().stream()
                    .map(x -> x.getProperty("name").toString()).collect(Collectors.toList());

            if (ourNode == null) {
                isNew = true;
                // Sanity check - the name in the request needs to match the name in the URL.
                if (!alm.getName().equals(name))
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(jsonerror("Name mismatch in annotation label creation request")).build();
                // Create the label and its properties and links
                ourNode = db.createNode(Nodes.ANNOTATIONLABEL);
                tradNode.createRelationshipTo(ourNode, ERelations.HAS_ANNOTATION_TYPE);
                ourNode.setProperty("name", alm.getName());
                existingLabels.add(alm.getName());
            } else {
                // We are updating an existing label, so we should delete its existing properties and links.
                // First check to make sure that, if we have changed the name, there is not already
                // another annotation label with this name
                if (!alm.getName().equals(name) && existingLabels.contains(alm.getName())) {
                    return Response.status(Response.Status.BAD_REQUEST).entity(jsonerror(
                            "An annotation label with name " + alm.getName() + " already exists")).build();
                }

                // LATER Sanity check that the properties / links being deleted (and not restored) aren't in use
                Relationship p = ourNode.getSingleRelationship(ERelations.HAS_PROPERTIES, Direction.OUTGOING);
                if (p != null) {
                    p.getEndNode().delete();
                    p.delete();
                }
                Relationship l = ourNode.getSingleRelationship(ERelations.HAS_LINKS, Direction.OUTGOING);
                if (l != null) {
                    l.getEndNode().delete();
                    l.delete();
                }

            }
            // Now reset the properties and links according to the model given.
            // Do we have any new properties?
            if (!alm.getProperties().isEmpty()) {
                Node pnode = db.createNode(Nodes.PROPERTIES);
                ourNode.createRelationshipTo(pnode, ERelations.HAS_PROPERTIES);
                ArrayList<String> allowedValues = new ArrayList<>(Arrays.asList("boolean", "long", "double",
                        "char", "String", "Point", "LocalDate", "OffsetTime", "LocalTime", "ZonedDateTime",
                        "LocalDateTime", "TemporalAmount"));
                for (String key : alm.getProperties().keySet()) {
                    // Validate the value - it needs to be a data type allowed by Neo4J.
                    String val = alm.getProperties().get(key);
                    if (allowedValues.contains(val) ||
                            allowedValues.contains(val.replace("[]", "")))
                        pnode.setProperty(key, val);
                    else
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity(jsonerror("Data type " + val + " not allowed as a Neo4J property")).build();
                }
            }
            // Do we have any links?
            if (!alm.getLinks().isEmpty()) {
                Node lnode = db.createNode(Nodes.LINKS);
                ourNode.createRelationshipTo(lnode, ERelations.HAS_LINKS);
                for (String key : alm.getLinks().keySet()) {
                    // Validate the value - the annotation label that the link points to
                    // has to exist for this tradition.
                    String val = alm.getLinks().get(key);
                    if (existingLabels.contains(val)) lnode.setProperty(key, val);
                    else
                        return Response.status(Response.Status.BAD_REQUEST).entity(jsonerror(
                                "Linked annotation label " + val + " not found in this tradition")).build();
                }
            }
            tx.success();
        } catch (Exception e) {
            return Response.serverError().entity(jsonerror(e.getMessage())).build();
        }
        return Response.status(isNew ? Response.Status.CREATED : Response.Status.OK)
                .entity(new AnnotationLabelModel(ourNode)).build();
    }

    @DELETE
    public Response deleteAnnotationLabel() {
        Node ourNode = lookupAnnotationLabel();
        if (ourNode == null) return Response.status(Response.Status.NOT_FOUND).build();
        try (Transaction tx = db.beginTx()) {
            // LATER Check for annotations on this tradition using this label, before we delete it

            // Delete the label and its properties/links
            for (Relationship r : ourNode.getRelationships(Direction.OUTGOING)) {
                r.getEndNode().delete();
                r.delete();
            }
            // Delete any reference to the label in any other label's linkset
            for (Node n : getExistingLabelsForTradition()) {
                Relationship l = n.getSingleRelationship(ERelations.HAS_LINKS, Direction.OUTGOING);
                if (l != null) {
                    for (String lname : l.getEndNode().getPropertyKeys()) {
                        if (l.getEndNode().getProperty(lname).toString().equals(name))
                            l.getEndNode().removeProperty(lname);
                    }
                }
            }
            tx.success();
        } catch (Exception e) {
            return Response.serverError().entity(jsonerror(e.getMessage())).build();
        }
        return Response.ok().build();
    }

    private Node lookupAnnotationLabel() {
        Node ourNode = null;
        try (Transaction tx = db.beginTx()) {
            Node tradNode = DatabaseService.getTraditionNode(tradId, db);
            Optional<Node> foundNode = DatabaseService.getRelated(tradNode, ERelations.HAS_ANNOTATION_TYPE)
                    .stream().filter(x -> x.getProperty("name", "").equals(name)).findFirst();
            if (foundNode.isPresent()) ourNode = foundNode.get();
            tx.success();
        }
        return ourNode;
    }

    private List<Node> getExistingLabelsForTradition() {
        Node tradNode = DatabaseService.getTraditionNode(tradId, db);
        List<Node> answer;
        try (Transaction tx = db.beginTx()) {
            answer = DatabaseService.getRelated(tradNode, ERelations.HAS_ANNOTATION_TYPE);
            tx.success();
            return answer;
        }
    }
}