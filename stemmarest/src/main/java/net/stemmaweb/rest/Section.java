package net.stemmaweb.rest;

import com.qmino.miredot.annotations.ReturnType;
import net.stemmaweb.exporter.DotExporter;
import net.stemmaweb.exporter.GraphMLExporter;
import net.stemmaweb.model.*;
import net.stemmaweb.services.DatabaseService;
import net.stemmaweb.services.GraphDatabaseServiceProvider;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.Uniqueness;

import javax.ws.rs.*;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

import static net.stemmaweb.rest.Util.jsonerror;
import static net.stemmaweb.rest.Util.jsonresp;
import static net.stemmaweb.services.ReadingService.AlignmentTraverse;
import static net.stemmaweb.services.ReadingService.addWitnessLink;
import static net.stemmaweb.services.ReadingService.recalculateRank;
import static net.stemmaweb.services.ReadingService.removePlaceholder;
import static net.stemmaweb.services.ReadingService.wouldGetCyclic;

/**
 * Comprises all the API calls related to a tradition section.
 *
 * @author tla
 */
public class Section {
    private GraphDatabaseService db;
    private String tradId;
    private String sectId;

    public Section(String traditionId, String sectionId) {
        GraphDatabaseServiceProvider dbServiceProvider = new GraphDatabaseServiceProvider();
        db = dbServiceProvider.getDatabase();
        tradId = traditionId;
        sectId = sectionId;
    }

    /*
     * Delegation
     */

    /**
     * @param sigil - The sigil of the requested witness
     */
    @Path("/witness/{sigil}")
    public Witness getWitnessFromSection(@PathParam("sigil") String sigil) {
        return new Witness(tradId, sectId, sigil);
    }


    // Base paths

    /**
     * Get the metadata for a section.
     *
     * @summary Get section
     * @return  JSON data for the requested section
     * @statuscode 200 - on success
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    @ReturnType(clazz = SectionModel.class)
    public Response getSectionInfo() {
        SectionModel result;
        if (!sectionInTradition())
            return Response.status(Response.Status.NOT_FOUND).entity(jsonerror("Tradition and/or section not found")).build();
        try (Transaction tx = db.beginTx()) {
            result = new SectionModel(db.getNodeById(Long.valueOf(sectId)));
            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity(jsonerror(e.getMessage())).build();
        }
        return Response.ok().entity(result).build();
    }

    /**
     * Update the metadata for a section.
     *
     * @summary Update section
     * @param newInfo - A JSON specification of the section update
     * @return  JSON data for the updated section
     * @statuscode 200 - on success
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    @ReturnType(clazz = SectionModel.class)
    public Response updateSectionInfo(SectionModel newInfo) {
        if (!sectionInTradition())
            return Response.status(Response.Status.NOT_FOUND).entity(jsonerror("Tradition and/or section not found")).build();
        try (Transaction tx = db.beginTx()) {
            Node thisSection = db.getNodeById(Long.valueOf(sectId));
            if (newInfo.getName() != null)
                thisSection.setProperty("name", newInfo.getName());
            if (newInfo.getLanguage() != null)
                thisSection.setProperty("language", newInfo.getLanguage());
            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity(jsonerror(e.getMessage())).build();
        }
        return getSectionInfo();
    }

    /**
     * Delete the specified section, and update the tradition's sequence of sections to
     * account for any resulting gap.
     *
     * @summary Delete section
     * @statuscode 200 - on success
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @DELETE
    @ReturnType("java.lang.Void")
    public Response deleteSection() {
        if (!sectionInTradition())
            return Response.status(Response.Status.NOT_FOUND).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(jsonerror("Tradition and/or section not found")).build();
        try (Transaction tx = db.beginTx()) {
            Node foundSection = db.getNodeById(Long.valueOf(sectId));
            if (foundSection != null) {
                // Find the section either side of this one and connect them if necessary.
                removeFromSequence(foundSection);
                // Collect all nodes and relationships that belong to this section.
                Set<Relationship> removableRelations = new HashSet<>();
                Set<Node> removableNodes = new HashSet<>();
                DatabaseService.returnTraditionSection(foundSection).nodes()
                        .forEach(x -> {
                            removableNodes.add(x);
                            x.getRelationships(Direction.BOTH).forEach(removableRelations::add);
                        });

                // Remove said nodes and relationships.
                removableRelations.forEach(Relationship::delete);
                removableNodes.forEach(Node::delete);
            }
            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(jsonerror(e.getMessage())).build();
        }

        return Response.ok().build();
    }

    /*
     * Collections
     */

    /**
     * Gets a list of all the witnesses of the section with the given id.
     *
     * @summary Get witnesses
     * @return A list of witness metadata
     * @statuscode 200 - on success
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @GET
    @Path("/witnesses")
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    @ReturnType("java.util.List<net.stemmaweb.model.WitnessModel>")
    public Response getAllWitnessInSection() {
        if (!sectionInTradition())
            return Response.status(Response.Status.NOT_FOUND).entity(jsonerror("Tradition and/or section not found")).build();

        ArrayList<Node> sectionWitnessNodes = collectSectionWitnesses();
        if (sectionWitnessNodes == null)
            return Response.serverError()
                    .entity(jsonerror("No witnesses found in section")).build();

        try (Transaction tx = db.beginTx()) {
            ArrayList<WitnessModel> sectionWits = new ArrayList<>();
            sectionWitnessNodes.forEach(x -> sectionWits.add(new WitnessModel(x)));
            tx.success();
            return Response.ok().entity(sectionWits).build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError()
                    .entity(jsonerror("Section witnesses could not be queried")).build();
        }
    }

    // Also used by the GraphML exporter
    public ArrayList<Node> collectSectionWitnesses() {
        ArrayList<Node> witnessList = new ArrayList<>();
        Node traditionNode = DatabaseService.getTraditionNode(tradId, db);
        Node sectionStart = DatabaseService.getStartNode(sectId, db);
        ArrayList<Node> traditionWitnesses = DatabaseService.getRelated(traditionNode, ERelations.HAS_WITNESS);
        try (Transaction tx = db.beginTx()) {
            for (Relationship relationship : sectionStart.getRelationships(ERelations.SEQUENCE)) {
                if (!relationship.hasProperty("witnesses"))
                    continue;
                for (String sigil : (String[]) relationship.getProperty("witnesses")) {
                    for (Node curWitness : traditionWitnesses) {
                        if (sigil.equals(curWitness.getProperty("sigil"))) {
                            witnessList.add(curWitness);
                            traditionWitnesses.remove(curWitness);
                            break;
                        }
                    }
                }
            }
            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return witnessList;
    }

    /**
     * Gets a list of all readings in the given tradition section.
     *
     * @summary Get readings
     * @return A list of reading metadata
     * @statuscode 200 - on success
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @GET
    @Path("/readings")
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    @ReturnType("java.util.List<net.stemmaweb.model.ReadingModel>")
    public Response getAllReadings() {
        if (!sectionInTradition())
            return Response.status(Response.Status.NOT_FOUND).entity(jsonerror("Tradition and/or section not found")).build();

        ArrayList<ReadingModel> readingModels = sectionReadings();
        if (readingModels == null)
            return Response.serverError().entity(jsonerror("No readings found in section")).build();
        return Response.ok(readingModels).build();
    }

    ArrayList<ReadingModel> sectionReadings() {
        ArrayList<ReadingModel> readingModels = new ArrayList<>();
        try (Transaction tx = db.beginTx()) {
            Node startNode = DatabaseService.getStartNode(sectId, db);
            if (startNode == null) throw new Exception("Section " + sectId + " has no start node");
            db.traversalDescription().depthFirst()
                    .relationships(ERelations.SEQUENCE, Direction.OUTGOING)
                    .evaluator(Evaluators.all())
                    .uniqueness(Uniqueness.NODE_GLOBAL).traverse(startNode)
                    .nodes().forEach(node -> readingModels.add(new ReadingModel(node)));
            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return readingModels;
    }

    /**
     * Gets a list of all relations defined within the given section.
     *
     * @summary Get relations
     * @return A list of relation metadata
     * @statuscode 200 - on success
     * @statuscode 404 - if no such tradition exists
     * @statuscode 500 - on failure, with an error message
     */
    @GET
    @Path("/relations")
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    @ReturnType("java.util.List<net.stemmaweb.model.RelationModel>")
    public Response getAllRelationships() {
        ArrayList<RelationModel> relList = sectionRelationships();

        if (relList == null) {
            return Response.serverError().entity(jsonerror("No relations found in section")).build();
        }
        return Response.ok(relList).build();
    }

    ArrayList<RelationModel> sectionRelationships() {
        ArrayList<RelationModel> relList = new ArrayList<>();

        Node startNode = DatabaseService.getStartNode(sectId, db);
        try (Transaction tx = db.beginTx()) {
            db.traversalDescription().depthFirst()
                    .relationships(ERelations.SEQUENCE, Direction.OUTGOING)
                    .uniqueness(Uniqueness.NODE_GLOBAL)
                    .traverse(startNode).nodes().forEach(
                    n -> n.getRelationships(ERelations.RELATED, Direction.OUTGOING).forEach(
                            r -> relList.add(new RelationModel(r)))
            );

            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return relList;
    }


    /*
     * Manipulation
     */

    /**
     * Move this section to a new place in the section sequence.
     *
     * @summary Reorder section
     * @param priorSectID - the ID of the section that should precede this one; "none" if this section should be first.
     * @statuscode 200 - on success
     * @statuscode 400 - if the priorSectId doesn't belong to the given tradition
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @PUT
    @Path("/orderAfter/{priorSectID}")
    @Produces(MediaType.TEXT_PLAIN)
    @ReturnType("java.lang.Void")
    public Response reorderSectionAfter(@PathParam("priorSectID") String priorSectID) {
        try (Transaction tx = db.beginTx()) {
            if (!sectionInTradition())
                return Response.status(Response.Status.NOT_FOUND).entity("Tradition and/or section not found").build();
            if (!priorSectID.equals("none") && !sectionInTradition(priorSectID))
                return Response.status(Response.Status.NOT_FOUND).entity("Requested prior section not found").build();

            Node thisSection = db.getNodeById(Long.valueOf(sectId));

            // Check that the requested prior section also exists and is part of the tradition
            Node priorSection = null;   // the requested prior section
            Node latterSection = null;  // the section after the requested prior
            if (priorSectID.equals("none")) {
                // There is no prior section, and the first section will become the latter one. Find it.
                ArrayList<Node> sectionNodes = DatabaseService.getSectionNodes(tradId, db);
                if (sectionNodes == null)
                    return Response.serverError().entity("Tradition has no sections").build();
                for (Node s : sectionNodes) {
                    if (!s.hasRelationship(ERelations.NEXT, Direction.INCOMING)) {
                        latterSection = s;
                        break;
                    }
                }
                if (latterSection == null)
                    return Response.serverError().entity("Could not find tradition's first section").build();

                // If we request the first section to go first, it should be a no-op.
                else if (latterSection.equals(thisSection))
                    return Response.ok().build();
            } else {
                priorSection = db.getNodeById(Long.valueOf(priorSectID));
                if (priorSection == null) {
                    return Response.status(Response.Status.NOT_FOUND).entity("Section " + priorSectID + "not found").build();
                }
                Node pnTradition = DatabaseService.getTraditionNode(priorSection, db);
                if (!pnTradition.getProperty("id").equals(tradId))
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("Section " + priorSectID + " doesn't belong to this tradition").build();

                if (priorSection.hasRelationship(Direction.OUTGOING, ERelations.NEXT)) {
                    Relationship oldSeq = priorSection.getSingleRelationship(ERelations.NEXT, Direction.OUTGOING);
                    latterSection = oldSeq.getEndNode();
                    oldSeq.delete();
                }
            }

            // Remove our node from its existing sequence
            removeFromSequence(thisSection);

            // Link it up to the prior if it exists
            if (priorSection != null) priorSection.createRelationshipTo(thisSection, ERelations.NEXT);
            // ...and to the old "next" if it exists
            if (latterSection != null) thisSection.createRelationshipTo(latterSection, ERelations.NEXT);
            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().build();
        }
        return Response.ok().build();
    }

    /**
     * Split a section into two at the given graph rank, and adjust the tradition's section order accordingly.
     * Returns a JSON response of the form {@code {"sectionId": <ID>}}, containing the ID of the new section.
     *
     * @summary Reorder section
     * @param rankstr - the rank at which the section should be split
     * @statuscode 200 - on success
     * @statuscode 400 - if the section doesn't contain the specified rank
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @POST
    @Path("/splitAtRank/{rankstr}")
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    @ReturnType("java.lang.Void")
    public Response splitAtRank (@PathParam("rankstr") String rankstr) {
        if (!sectionInTradition())
            return Response.status(Response.Status.NOT_FOUND).entity(jsonerror("Tradition and/or section not found")).build();

        Long rank = Long.valueOf(rankstr);
        // Get the reading(s) at the given rank, and at the prior rank
        Node startNode = DatabaseService.getStartNode(sectId, db);
        Long newSectionId;

        try (Transaction tx = db.beginTx()) {
            Node thisSection = db.getNodeById(Long.valueOf(sectId));

            // Make a list of readings that belong to the requested rank as well
            // as the prior rank
            ArrayList<Node> thisRank = new ArrayList<>();
            ArrayList<Node> priorRank = new ArrayList<>();
            ResourceIterable<Node> sectionReadings = db.traversalDescription().depthFirst()
                    .relationships(ERelations.SEQUENCE, Direction.OUTGOING)
                    .evaluator(Evaluators.all())
                    .uniqueness(Uniqueness.NODE_GLOBAL).traverse(startNode)
                    .nodes();
            for (Node n : sectionReadings) {
                Long nrank = (Long) n.getProperty("rank");
                if (rank.equals(nrank)) {
                    thisRank.add(n);
                } else if (rank.equals(nrank + 1)) {
                    priorRank.add(n);
                }
            }

            // Make sure we have readings at the requested rank in this section
            if (thisRank.size() == 0)
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(jsonerror("Rank not found within section")).build();

            // Make a new section node and insert it into the sequence
            Node newSection = db.createNode(Nodes.SECTION);
            DatabaseService.getTraditionNode(thisSection, db).createRelationshipTo(newSection, ERelations.PART);
            newSection.setProperty("name", thisSection.getProperty("name") + " split");
            newSectionId = newSection.getId();
            Section newSectionRest = new Section(tradId, String.valueOf(newSection.getId()));
            Response reorder = newSectionRest.reorderSectionAfter(sectId);
            if (reorder.getStatus() != Response.Status.OK.getStatusCode())
                return reorder;

            // Attach the old END node to the new section
            Node sectionEnd = DatabaseService.getEndNode(sectId, db);
            sectionEnd.getSingleRelationship(ERelations.HAS_END, Direction.INCOMING).delete();
            newSection.createRelationshipTo(sectionEnd, ERelations.HAS_END);

            // Close off the prior rank with a new END node, and the requested rank with a new
            // START node
            Node newEnd = db.createNode(Nodes.READING);
            newEnd.setProperty("is_end", true);
            newEnd.setProperty("rank", sectionEnd.getProperty("rank"));
            thisSection.createRelationshipTo(newEnd, ERelations.HAS_END);
            Node newStart = db.createNode(Nodes.READING);
            newStart.setProperty("is_start", true);
            newStart.setProperty("rank", 0L);
            newSection.createRelationshipTo(newStart, ERelations.COLLATION);
            for (Node reading : priorRank)
                for (Relationship rel : reading.getRelationships(Direction.OUTGOING))
                    if (rel.isType(ERelations.SEQUENCE) || rel.isType(ERelations.LEMMA_TEXT)) {
                        Relationship outRel = reading.createRelationshipTo(newEnd, rel.getType());
                        Relationship inRel = newStart.createRelationshipTo(rel.getEndNode(), rel.getType());
                        rel.getPropertyKeys().forEach(x -> outRel.setProperty(x, rel.getProperty(x)));
                        rel.getPropertyKeys().forEach(x -> inRel.setProperty(x, rel.getProperty(x)));
                        rel.delete();
                    }
            for (Node reading : thisRank) {
                for (Relationship rel : reading.getRelationships(Direction.INCOMING)) {
                    if (rel.getStartNode().equals(newStart))
                        continue;
                    if (rel.isType(ERelations.SEQUENCE) || rel.isType(ERelations.LEMMA_TEXT)) {
                        Relationship inRel = rel.getStartNode().createRelationshipTo(newEnd, rel.getType());
                        Relationship outRel = newStart.createRelationshipTo(reading, rel.getType());
                        rel.getPropertyKeys().forEach(x -> inRel.setProperty(x, rel.getProperty(x)));
                        rel.getPropertyKeys().forEach(x -> outRel.setProperty(x, rel.getProperty(x)));
                        rel.delete();
                    }
                }
            }

            // TODO Remove any superfluous witnesses in each split section
            Node sectionStart = DatabaseService.getStartNode(sectId, db);
            for (Relationship r : sectionStart.getRelationships(ERelations.SEQUENCE, Direction.OUTGOING))
                if (r.getEndNode().hasProperty("is_end"))
                    r.delete();
            for (Relationship r : newStart.getRelationships(ERelations.SEQUENCE, Direction.OUTGOING))
                if (r.getEndNode().hasProperty("is_end"))
                    r.delete();

            // Collect all readings from the second section and alter their section metadata
            final Long newId = newSection.getId();
            db.traversalDescription().depthFirst().expand(new AlignmentTraverse())
                    .uniqueness(Uniqueness.NODE_GLOBAL).traverse(newStart).nodes()
                    .stream().forEach(x -> x.setProperty("section_id", newId));

            // Re-initialize the ranks on the new section
            recalculateRank(newStart);

            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity(jsonerror(e.getMessage())).build();
        }
        return Response.ok().entity(jsonresp("sectionId", newSectionId)).build();
    }

    /**
     * Merge two sections into one, and adjust the tradition's section order accordingly. The
     * specified sections must be contiguous, and will be merged according to their existing order.
     *
     * @summary Merge sections
     * @param otherId - the rank at which the section should be split
     * @statuscode 200 - on success
     * @statuscode 400 - if the sections are not contiguous
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @POST
    @Path("/merge/{otherId}")
    @Produces(MediaType.TEXT_PLAIN)
    @ReturnType("java.lang.Void")
    public Response mergeSections (@PathParam("otherId") String otherId) {
        if (!sectionInTradition())
            return Response.status(Response.Status.NOT_FOUND).entity("Tradition and/or section not found").build();
        if (!sectionInTradition(otherId))
            return Response.status(Response.Status.NOT_FOUND).entity("Requested other section not found").build();

        try (Transaction tx = db.beginTx()) {
            // Get this node, and see which direction we're merging
            Node thisSection = db.getNodeById(Long.valueOf(sectId));
            Node firstSection = null;
            Node secondSection = null;
            for (Relationship r : thisSection.getRelationships(ERelations.NEXT)) {
                if (otherId.equals(String.valueOf(r.getEndNode().getId())))
                    secondSection = r.getEndNode();
                else if (otherId.equals(String.valueOf(r.getStartNode().getId())))
                    firstSection = r.getStartNode();
            }
            if (firstSection == null) {
                if (secondSection == null)
                    return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Requested sections are not neighbours").build();
                else
                    firstSection = thisSection;
            } else
                secondSection = thisSection;

            // Move relationships from the old start & end nodes
            Node oldEnd = DatabaseService.getEndNode(String.valueOf(firstSection.getId()), db);
            Node oldStart = DatabaseService.getStartNode(String.valueOf(secondSection.getId()), db);
            Node trueStart = DatabaseService.getStartNode(String.valueOf(firstSection.getId()), db);
            Node trueEnd = DatabaseService.getEndNode(String.valueOf(secondSection.getId()), db);

            // Collect all readings from the second section and alter their section metadata
            final Long keptId = firstSection.getId();
            db.traversalDescription().depthFirst().expand(new AlignmentTraverse())
                    .uniqueness(Uniqueness.NODE_GLOBAL).traverse(oldStart).nodes()
                    .stream().forEach(x -> x.setProperty("section_id", keptId));

            // Collect the last readings from the first section, for later rank recalculation
            List<Node> firstSectionEnd = new ArrayList<>();
            oldEnd.getRelationships(Direction.INCOMING, ERelations.LEMMA_TEXT, ERelations.SEQUENCE)
                    .forEach(x -> firstSectionEnd.add(x.getStartNode()));

            // First we turn oldEnd and oldStart into placeholder readings, linked to each other
            oldStart.getSingleRelationship(ERelations.COLLATION, Direction.INCOMING).delete();
            oldEnd.getSingleRelationship(ERelations.HAS_END, Direction.INCOMING).delete();
            oldEnd.setProperty("is_placeholder", true);
            oldStart.setProperty("is_placeholder", true);
            HashSet<String> oldWitnesses = new HashSet<>();
            HashSet<String> newWitnesses = new HashSet<>();
            for (Relationship r : oldEnd.getRelationships(ERelations.SEQUENCE))
                for (String key : r.getPropertyKeys())
                    oldWitnesses.addAll(Arrays.asList((String[]) r.getProperty(key)));
            for (Relationship r : oldStart.getRelationships(ERelations.SEQUENCE))
                for (String key : r.getPropertyKeys())
                    newWitnesses.addAll(Arrays.asList((String[]) r.getProperty(key)));
            newWitnesses.stream().filter(x -> !oldWitnesses.contains(x))
                    .forEach(x -> addWitnessLink(trueStart, oldEnd, x, "witnesses"));
            oldWitnesses.stream().filter(x -> !newWitnesses.contains(x))
                    .forEach(x -> addWitnessLink(oldStart, trueEnd, x, "witnesses"));
            oldWitnesses.addAll(newWitnesses);
            Relationship link = oldEnd.createRelationshipTo(oldStart, ERelations.SEQUENCE);
            link.setProperty("witnesses", oldWitnesses.toArray(new String[0]));

            // Reconfigure the lemma text link, if there is one
            Relationship plr = oldEnd.getSingleRelationship(ERelations.LEMMA_TEXT, Direction.INCOMING);
            Relationship nlr = oldStart.getSingleRelationship(ERelations.LEMMA_TEXT, Direction.OUTGOING);
            if (plr != null && nlr != null)
                plr.getStartNode().createRelationshipTo(nlr.getEndNode(), ERelations.LEMMA_TEXT);
            if (plr != null) plr.delete();
            if (nlr != null) nlr.delete();

            // Remove each placeholder in turn
            removePlaceholder(oldEnd);
            removePlaceholder(oldStart);

            // Move the second end node to the first section
            trueEnd.getSingleRelationship(ERelations.HAS_END, Direction.INCOMING).delete();
            firstSection.createRelationshipTo(trueEnd, ERelations.HAS_END);

            // Adjust the section ordering and delete the second section
            removeFromSequence(secondSection);
            secondSection.getSingleRelationship(ERelations.PART, Direction.INCOMING).delete();
            secondSection.delete();

            // Re-initialize the ranks starting from the final readings of the first section.
            for (Node n : firstSectionEnd)
                recalculateRank(n);

            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity(e.getMessage()).build();
        }
        return Response.ok().build();
    }

    /*
     * Analysis
     */

    /**
     * Returns a list of sets of readings that could potentially be identical - that is, they
     * have the same text and same joining properties, and are co-located. This is used to
     * identify possible inconsistencies in the collation.
     *
     * @summary List mergeable readings
     * @param startRank - where to start
     * @param endRank   - where to end
     * @return lists of readings that may be merged.
     * @statuscode 200 - on success
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @GET
    @Path("/mergeablereadings/{startRank}/{endRank}")
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    @ReturnType("java.util.List<java.util.List<net.stemmaweb.model.ReadingModel>>")
    public Response getCouldBeIdenticalReadings(
            @PathParam("startRank") long startRank,
            @PathParam("endRank") long endRank) {
        Node startNode = DatabaseService.getStartNode(sectId, db);
        if (startNode == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(jsonerror("Tradition and/or section not found")).build();
        }

        List<List<ReadingModel>> couldBeIdenticalReadings;
        try (Transaction tx = db.beginTx()) {
            List<Node> questionedReadings = getReadingsBetweenRanks(
                    startRank, endRank, startNode);

            couldBeIdenticalReadings = getCouldBeIdenticalAsList(questionedReadings);
            tx.success();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity(jsonerror(e.getMessage())).build();
        }
        return Response.ok(couldBeIdenticalReadings).build();
    }

    /**
     * Makes separate lists for every group of readings with identical text and
     * different ranks and send the list for further test
     *
     * @param questionedReadings -
     * @return list of lists of identical readings
     */
    private List<List<ReadingModel>> getCouldBeIdenticalAsList(
            List<Node> questionedReadings) {

        List<List<ReadingModel>> couldBeIdenticalReadings = new ArrayList<>();
        HashSet<Long> processed = new HashSet<>();

        for (Node nodeA : questionedReadings) {
            if (processed.contains(nodeA.getId()))
                continue;
            List<Node> sameText = questionedReadings.stream().filter(x -> !x.equals(nodeA)
                && x.getProperty("text").equals(nodeA.getProperty("text")))
                    .collect(Collectors.toList());
            for (Node n : sameText) {
                if (processed.contains(n.getId()))
                    continue;
                if (!wouldGetCyclic(nodeA, n)) {
                    ArrayList<ReadingModel> pair = new ArrayList<>();
                    pair.add(new ReadingModel(nodeA));
                    pair.add(new ReadingModel(n));
                    couldBeIdenticalReadings.add(pair);
                }
            }
            processed.add(nodeA.getId());
        }
        return couldBeIdenticalReadings;
    }

    // Retrieve all readings of a tradition between two ranks as Nodes
    private List<Node> getReadingsBetweenRanks(long startRank, long endRank, Node startNode) {
        List<Node> readings;
        PathExpander e = new AlignmentTraverse();
        try (Transaction tx = db.beginTx()) {
            readings = db.traversalDescription().depthFirst()
                    .expand(e).uniqueness(Uniqueness.NODE_GLOBAL)
                    .traverse(startNode).nodes().stream()
                    .filter(x -> startRank <= Long.valueOf(x.getProperty("rank").toString()) &&
                                 endRank >= Long.valueOf(x.getProperty("rank").toString()))
                    .collect(Collectors.toList());
            tx.success();
        }
        return readings;
    }


    /**
     * Get all readings which have the same text and the same rank, between the given ranks.
     * This is a constrained version of {@code mergeablereadings}.
     *
     * @summary Find identical readings
     * @param startRank the rank from where to start the search
     * @param endRank   the rank at which to end the search
     * @return a list of lists of identical readings
     * @statuscode 200 - on success
     * @statuscode 404 - if no such tradition or section exists
     */
    // TODO refactor all these traversals somewhere!
    @GET
    @Path("/identicalreadings/{startRank}/{endRank}")
    @Produces(MediaType.APPLICATION_JSON + "; charset=utf-8")
    @ReturnType("java.util.List<java.util.List<net.stemmaweb.model.ReadingModel>>")
    public Response getIdenticalReadings(@PathParam("startRank") long startRank,
                                         @PathParam("endRank") long endRank) {
        ArrayList<List<ReadingModel>> identicalReadings = collectIdenticalReadings(startRank, endRank);
        if (identicalReadings == null)
            return Response.status(Response.Status.NOT_FOUND).entity(jsonerror("no identical readings were found")).build();

        return Response.ok(identicalReadings).build();
    }

    // We want access within net.stemmaweb.parser as well
    public ArrayList<List<ReadingModel>> collectIdenticalReadings(long startRank, long endRank) {
        Node startNode = DatabaseService.getStartNode(sectId, db);
        if (startNode == null) return null;

        ArrayList<List<ReadingModel>> identicalReadings;
        try {
            ArrayList<ReadingModel> readingModels =
                    getAllReadingsFromSectionBetweenRanks(startNode, startRank, endRank);
            identicalReadings = identifyIdenticalReadings(readingModels, startRank, endRank);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        ArrayList<List<ReadingModel>> result = identicalReadings.stream().filter(x -> x.size() > 0)
                .collect(Collectors.toCollection(ArrayList::new));
        if (result.size() == 0) return null;
        return result;
    }

    // Retrieve all readings of a tradition between two ranks as ReadingModels
    private ArrayList<ReadingModel> getAllReadingsFromSectionBetweenRanks(
            Node startNode, long startRank, long endRank) {
        ArrayList<ReadingModel> readingModels = new ArrayList<>();
        getReadingsBetweenRanks(startRank, endRank, startNode)
                .forEach(x -> readingModels.add(new ReadingModel(x)));
        readingModels.sort(Comparator.comparing(ReadingModel::getRank));
        return readingModels;
    }

    // Gets identical readings in a list of ReadingModels sorted by rank.
    private ArrayList<List<ReadingModel>> identifyIdenticalReadings(
            ArrayList<ReadingModel> readingModels, long startRank, long endRank) {
        ArrayList<List<ReadingModel>> identicalReadingsList = new ArrayList<>();

        HashMap<String, List<ReadingModel>> rankSet = new HashMap<>();
        for (ReadingModel rm : readingModels) {
            String normReading = Normalizer.normalize(rm.getText(), Normalizer.Form.NFC);
            if (rm.getRank() > endRank)
                break;
            if (rm.getRank() > startRank) {
                for (String k : rankSet.keySet())
                    if (rankSet.get(k).size() > 1)
                        identicalReadingsList.add(rankSet.get(k));
                rankSet.clear();
                rankSet.put(normReading, new ArrayList<>(Collections.singletonList(rm)));
                startRank = rm.getRank();
            }
            else if (rankSet.containsKey(normReading))
                rankSet.get(normReading).add(rm);
            else
                rankSet.put(normReading, new ArrayList<>(Collections.singletonList(rm)));
        }
        return identicalReadingsList;
    }



    /*
     * Export
     */

    // Export the dot / SVG for a particular section
    /**
     * Returns a GraphML file that describes the specified section and its data.
     *
     * @summary Download GraphML
     * @param includeWitnesses - Whether or not to include witness information in the XML
     * @return GraphML description of the section subgraph
     * @statuscode 200 - on success
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @GET
    @Path("/graphml")
    @Produces(MediaType.APPLICATION_XML + "; charset=utf-8")
    @ReturnType("java.lang.Void")
    public Response getGraphML(@DefaultValue("false") @QueryParam("include_witnesses") Boolean includeWitnesses) {
        if (DatabaseService.getTraditionNode(tradId, db) == null)
            return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_PLAIN_TYPE)
                    .entity("No such tradition found").build();

        GraphMLExporter exporter = new GraphMLExporter();
        return exporter.writeNeo4J(tradId, sectId, includeWitnesses);
    }

    // Export the dot / SVG for a particular section
    /**
     * Returns a GraphViz dot file that describes the specified section and its data.
     *
     * @summary Download GraphViz
     * @param includeRelatedRelationships - Whether or not to include RELATED edges in the dot
     * @param displayAllSigla - Whether to always display sigil lists; if false, use 'majority' label
     * @param showNormalForms - Whether to display the normal (canonical) form of a reading alongside
     *                        its literal form
     * @param normalise - Produce a graph based on the normal forms rather than the literal ones;
     *                  overrides show_normal
     * @return Plaintext dot format
     * @statuscode 200 - on success
     * @statuscode 404 - if no such tradition or section exists
     * @statuscode 500 - on failure, with an error message
     */
    @GET
    @Path("/dot")
    @Produces(MediaType.TEXT_PLAIN + "; charset=utf-8")
    @ReturnType("java.lang.String")
    public Response getDot(@DefaultValue("false") @QueryParam("include_relations") Boolean includeRelatedRelationships,
                           @DefaultValue("false") @QueryParam("show_normal") Boolean showNormalForms,
                           @DefaultValue("false") @QueryParam("normalise") Boolean normalise,
                           @DefaultValue("false") @QueryParam("expand_sigla") Boolean displayAllSigla) {
        if (DatabaseService.getTraditionNode(tradId, db) == null)
            return Response.status(Response.Status.NOT_FOUND).entity("No such tradition found").build();

        // Put our options into an object
        DisplayOptionModel dm = new DisplayOptionModel(
                includeRelatedRelationships, showNormalForms, normalise, displayAllSigla);
        // Make the dot.
        DotExporter exporter = new DotExporter(db);
        return exporter.writeNeo4J(tradId, sectId, dm);
    }

    // Export a list of variants for a section

    // For use in a transaction!
    private void removeFromSequence (Node thisSection) {
        Node priorSection = null;
        Node nextSection = null;
        if (thisSection.hasRelationship(Direction.INCOMING, ERelations.NEXT)) {
            Relationship incomingRel = thisSection.getSingleRelationship(ERelations.NEXT, Direction.INCOMING);
            priorSection = incomingRel.getStartNode();
            incomingRel.delete();
        }
        if (thisSection.hasRelationship(Direction.OUTGOING, ERelations.NEXT)) {
            Relationship outgoingRel = thisSection.getSingleRelationship(ERelations.NEXT, Direction.OUTGOING);
            nextSection = outgoingRel.getEndNode();
            outgoingRel.delete();
        }
        if (priorSection != null && nextSection != null) {
            priorSection.createRelationshipTo(nextSection, ERelations.NEXT);
        }
    }

    private Boolean sectionInTradition() {
        return sectionInTradition(sectId);
    }

    private Boolean sectionInTradition(String aSectionId) {
        Node traditionNode = DatabaseService.getTraditionNode(tradId, db);
        if (traditionNode == null)
            return false;

        boolean found = false;
        try (Transaction tx = db.beginTx()) {
            for (Node s : DatabaseService.getRelated(traditionNode, ERelations.PART)) {
                if (s.getId() == Long.valueOf(aSectionId)) {
                    found = true;
                }
            }
            tx.success();
        }
        return found;
    }
}
