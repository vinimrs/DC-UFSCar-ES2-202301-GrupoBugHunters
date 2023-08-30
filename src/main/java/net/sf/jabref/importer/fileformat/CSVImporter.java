package net.sf.jabref.importer.fileformat;

import java.io.*;
import java.util.*;

import net.sf.jabref.importer.ImportFormatReader;
import net.sf.jabref.importer.OutputPrinter;
import net.sf.jabref.model.entry.BibEntry;
import net.sf.jabref.model.entry.BibtexEntryTypes;

public class CSVImporter extends ImportFormat {

    //Return the name of this import format
    @Override
    public String getFormatName() {
        return "CSV";
    }

    @Override
    public String getDescription() {
        return "CSV Importer";
    }

    //Check if the file is in the correct format for this importer.
    @Override
    public boolean isRecognizedFormat(InputStream stream) throws IOException {
        return true;
    }

    //Parse the inputs in the selected source and return a list of BibEntry objects
    @Override
    public List<BibEntry> importEntries(InputStream stream, OutputPrinter printer) throws IOException {

        List<BibEntry> items = new ArrayList<>();
        BufferedReader reader = new BufferedReader(ImportFormatReader.getReaderDefaultEncoding(stream));

        String line = reader.readLine();
        while (line != null) {
            if (!line.contains("author") && !line.trim().isEmpty()) {

                BibEntry entry = new BibEntry();

                String[] fields = line.split(";");

                if (fields[0].equals("Inproceedings") || fields[0].equals("inproceedings")) {

                    entry.setType(BibtexEntryTypes.INPROCEEDINGS);
                    entry.setField("author", fields[1]);
                    entry.setField("title", fields[2]);
                    entry.setField("booktitle", fields[3]);
                    entry.setField("year", fields[4]);
                    entry.setField("bibtexkey", fields[5]);

                    items.add(entry);

            }

                if (fields[0].equals("Book") || fields[0].equals("book")) {
                    entry.setType(BibtexEntryTypes.BOOK);
                    entry.setField("title", fields[1]);
                    entry.setField("publisher", fields[2]);
                    entry.setField("year", fields[3]);
                    entry.setField("author", fields[4]);
                    entry.setField("editor", fields[5]);
                    entry.setField("bibtexkey", fields[6]);

                    items.add(entry);

                }
            }
            line = reader.readLine();
            }
        return items;
    }

    //Get CSV
    @Override
    public String getExtensions() {
        return "csv";
    }
}