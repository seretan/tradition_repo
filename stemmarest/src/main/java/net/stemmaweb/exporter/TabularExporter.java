package net.stemmaweb.exporter;

import com.opencsv.CSVWriter;
import net.stemmaweb.model.AlignmentModel;
import net.stemmaweb.model.WitnessTokensModel;
import net.stemmaweb.model.ReadingModel;
import net.stemmaweb.services.DatabaseService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Transaction;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A class for writing a graph out to various forms of table: JSON, CSV, Excel, etc.
 */
public class TabularExporter {

    private GraphDatabaseService db;
    public TabularExporter(GraphDatabaseService db){
        this.db = db;
    }

    public Response exportAsJSON(String tradId, String conflate, List<String> sectionList) {
        ArrayList<Node> traditionSections;
        try {
            traditionSections = getSections(tradId, sectionList);
            if(traditionSections==null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            return Response.ok(getTraditionAlignment(traditionSections, conflate),
                    MediaType.APPLICATION_JSON_TYPE).build();
        } catch (TabularExporterException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
    }


    public Response exportAsCSV(String tradId, char separator, String conflate, List<String> sectionList) {
        ArrayList<Node> traditionSections;
        AlignmentModel wholeTradition;
        try {
            // Get our list of sections
            traditionSections = getSections(tradId, sectionList);
            if(traditionSections==null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            // Get the alignment model from exportAsJSON, and then turn that into CSV.
            wholeTradition = getTraditionAlignment(traditionSections, conflate);
        } catch (TabularExporterException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.serverError().entity(e.getMessage()).build();
        }

        // Got this far? Turn it into CSV.
        // The CSV will go into a string that we can return.
        StringWriter sw = new StringWriter();
        CSVWriter writer = new CSVWriter(sw, separator);

        // First write out the witness list
        writer.writeNext(wholeTradition.getAlignment().stream()
                .map(WitnessTokensModel::getWitness).toArray(String[]::new));

        // Now write out the normal_form or text for the reading in each "row"
        for (int i = 0; i < wholeTradition.getLength(); i++) {
            AtomicInteger ai = new AtomicInteger(i);
            writer.writeNext(wholeTradition.getAlignment().stream()
                    .map(x -> {
                        ReadingModel rm = x.getTokens().get(ai.get());
                        return rm == null ? null : rm.normalized();
                    }).toArray(String[]::new));
        }

        // Close off the CSV writer and return
        try {
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
            return Response.serverError().entity(e.getMessage()).build();
        }
        return Response.ok(sw.toString(), MediaType.TEXT_PLAIN_TYPE).build();
    }

    private ArrayList<Node> getSections(String tradId, List<String> sectionList)
    throws TabularExporterException {
        ArrayList<Node> traditionSections = DatabaseService.getSectionNodes(tradId, db);
        // Does the tradition exist in the first place?
        if (traditionSections == null) return null;

        // Are we requesting all sections?
        if (sectionList.size() == 0) return traditionSections;

        // Do the real work
        ArrayList<Node> collectedSections = new ArrayList<>();
        for (String sectionId : sectionList) {
            try (Transaction tx = db.beginTx()) {
                collectedSections.add(db.getNodeById(Long.valueOf(sectionId)));
                tx.success();
            } catch (NotFoundException e) {
                throw new TabularExporterException("Section " + sectionId + " not found in tradition");
            }
        }
        return collectedSections;
    }

    private AlignmentModel getTraditionAlignment(ArrayList<Node> traditionSections, String collapseRelated)
            throws Exception {
        // Make a new alignment model that has a column for every witness layer across the requested sections.

        // For each section, get the model. Keep track of which layers in which witnesses we have
        // seen with a set.
        HashSet<String> allWitnesses = new HashSet<>();
        ArrayList<AlignmentModel> tables = new ArrayList<>();
        for (Node sectionNode : traditionSections) {
            AlignmentModel asJson = new AlignmentModel(sectionNode, collapseRelated);
            // Save the alignment to our tables list
            tables.add(asJson);
            // Save the witness -> column mapping to our map
            for (WitnessTokensModel witRecord : asJson.getAlignment()) {
                allWitnesses.add(witRecord.constructSigil());
            }
        }

        // Now make an alignment model containing all witness layers present in allWitnesses, filling in
        // if necessary either nulls or the base witness per witness layer, per section.
        AlignmentModel wholeTradition = new AlignmentModel();
        List<String> sortedWits = new ArrayList<>(allWitnesses);
        Collections.sort(sortedWits);
        for (String sigil : sortedWits) {
            String[] parsed = WitnessTokensModel.parseSigil(sigil);

            // Set up the tradition-spanning witness token model for this witness
            WitnessTokensModel wholeWitness = new WitnessTokensModel();
            wholeWitness.setWitness(parsed[0]);
            if (parsed[1] != null) wholeWitness.setLayer(parsed[1]);
            wholeWitness.setTokens(new ArrayList<>());
            // Now fill in tokens from each section in turn.
            for (AlignmentModel aSection : tables) {
                // Find the WitnessTokensModel corresponding to wit, if it exists
                Optional<WitnessTokensModel> thisWitness = aSection.getAlignment().stream()
                        .filter(x -> x.constructSigil().equals(sigil)).findFirst();
                if (!thisWitness.isPresent()) {
                    // Try again for the base witness
                    thisWitness = aSection.getAlignment().stream()
                            .filter(x -> x.getWitness().equals(parsed[0]) && !x.hasLayer()).findFirst();
                }
                if (thisWitness.isPresent()) {
                    WitnessTokensModel witcolumn = thisWitness.get();
                    wholeWitness.getTokens().addAll(witcolumn.getTokens());
                } else {
                    // Add a bunch of nulls
                    wholeWitness.getTokens().addAll(new ArrayList<>(Collections.nCopies((int) aSection.getLength(), null)));
                }
            }
            // Add the WitnessTokensModel to the new AlignmentModel.
            wholeTradition.addWitness(wholeWitness);
        }
        // Record the length of the whole alignment
        wholeTradition.setLength(wholeTradition.getAlignment().get(0).getTokens().size());
        return wholeTradition;
    }

    private static class TabularExporterException extends Exception {
        TabularExporterException (String message) {
            super(message);
        }
    }

}
