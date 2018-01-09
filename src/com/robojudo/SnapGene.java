package com.robojudo;

import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class SnapGene
{
    public static void main(String[] args) throws IOException {
        FileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().endsWith(".dna"))
                {
                    System.out.println("parsing: " + file.toString());
                    FileInputStream fis = new FileInputStream(file.toFile());
                    boolean negativeTest = file.getFileName().toString().startsWith("bad_");
                    if (negativeTest) {
                        try {
                            SnapGeneDoc doc = parse(fis);
                        }
                        catch (Exception e) {
                            System.out.println("Caught expected exception parsing negative test case: " + e.toString());
                            e.printStackTrace();
                        }
                    }
                    else {
                        SnapGeneDoc doc = parse(fis);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        };

        Path p = Paths.get(args[0]);
        Files.walkFileTree(p, visitor);
    }


    public static SnapGeneDoc parse(InputStream is) throws IOException {

        SnapGeneDoc doc = new SnapGeneDoc();

        byte[] bs = new byte[5];
        while (true)
        {
            int count = is.read(bs);
            if (count != 5)
                break;

            int type = bs[0];
            int len = ByteBuffer.wrap(bs, 1, 4).getInt();

            byte[] data = new byte[len];
            if (len != is.read(data))
                throw new IllegalArgumentException("Expected " + len + " bytes");

            Segment seg;
            switch (type)
            {
                case 0: {
                    if (doc.dna != null)
                        throw new IllegalArgumentException("Duplicate DNA segment");
                    seg = doc.dna = DNASegment.parse(data);
                    break;
                }

                // TODO
//                case 5:  seg = PrimersSegment.parse(data);           break;

                case 6: {
                    if (doc.notes != null)
                        throw new IllegalArgumentException("Duplicate notes segment");
                    seg = doc.notes = NotesSegment.parse(data);
                    break;
                }

                // TODO
//                case 8:  seg = PropertiesSegment.parse(data);        break;

                case 9: {
                    if (doc.desc != null)
                        throw new IllegalArgumentException("Duplicate description segment");
                    seg = doc.desc = DescriptionSegment.parse(data);
                    break;
                }

                case 10: {
                    if (doc.features != null)
                        throw new IllegalArgumentException("Duplicate features segment");
                    seg = doc.features = FeaturesSegment.parse(data);
                    break;
                }

                // Unsupported segments
                case 1: // Compressed DNA (internal to History Tree segment)
                case 7: // History Tree
                case 11: // History Node (internal to History Tree segment)
                case 16: // Alignable Sequence (contains internal segments)
                case 17: // Alignable Sequences Summary
                case 18: // Sequence Trace (internal to Alignable Sequence segment (type=16))
                case 19: // Uracil Positions
                case 20: // Custom DNA Colors
                default: {
                    seg = UnsupportedSegment.parse(type, data);
                    break;
                }
            }

            // collect all segments in order
            doc.segments.add(seg);
        }

        if (doc.desc == null)
            throw new IllegalArgumentException("SnapGene description segment not found");

        return doc;
    }

    static Document createDocument(String xml)
    {
        try {
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            return db.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("Error parsing XML", e);
        }
    }

    static List<Element> getElements(Element parent)
    {
        List<Element> ret = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i ++)
        {
            Node node = nl.item(i);
            if (!(node instanceof Element))
                continue;
            ret.add((Element)node);
        }
        return ret;
    }

    static List<Element> getElementsByTagName(Element parent, String tagName)
    {
        List<Element> ret = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i ++)
        {
            Node node = nl.item(i);
            if (!(node instanceof Element))
                continue;
            Element child = (Element) node;
            if (child.getTagName().equals(tagName))
                ret.add(child);
        }
        return ret;
    }

    static Element getElementByTagName(Element parent, String tagName)
    {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i ++)
        {
            Node node = nl.item(i);
            if (!(node instanceof Element))
                continue;
            Element child = (Element) node;
            if (child.getTagName().equals(tagName))
                return child;
        }
        return null;
    }

    static String getInnerText(Element el)
    {
        NodeList nl = el.getChildNodes();
        int len = nl.getLength();
        if (len == 0)
            return "";
        if (len == 1)
            return nl.item(0).getNodeValue();
        StringBuilder ret = new StringBuilder();
        for (int i = 0; i < nl.getLength(); i ++)
            ret.append(nl.item(i).getNodeValue());
        return ret.toString();
    }

    static String getElementInnerText(Element parent, String tagName)
    {
        Element child = getElementByTagName(parent, tagName);
        if (child == null)
            return null;
        return getInnerText(child);
    }

    /** Returns attribute value using the given name or alternate names. */
    static String getAttribute(Element el, String name, String... alternateNames)
    {
        if (el.hasAttribute(name))
            return el.getAttribute(name);

        for (String attrName : alternateNames)
            if (el.hasAttribute(attrName))
                return el.getAttribute(attrName);

        return null;
    }

    /** Returns attribute value using the given name or alternate names. */
    static String getAttribute(Element el, QName name, QName... alternateNames)
    {
        if (el.hasAttributeNS(name.getNamespaceURI(), name.getLocalPart()))
            return el.getAttributeNS(name.getNamespaceURI(), name.getLocalPart());

        for (QName attrName : alternateNames)
            if (el.hasAttributeNS(attrName.getNamespaceURI(), attrName.getLocalPart()))
                return el.getAttributeNS(attrName.getNamespaceURI(), attrName.getLocalPart());

        return null;
    }

    static Set<String> getOtherAttributeNames(Element el, Set<String> except)
    {
        Set<String> attrs = new HashSet<>();
        NamedNodeMap nnm = el.getAttributes();
        int len = nnm.getLength();
        for (int i = 0; i < len; i++)
        {
            Node node = nnm.item(i);
            if (node instanceof Attr)
            {
                String name = ((Attr)node).getName();
                if (except.contains(name))
                    continue;
                attrs.add(name);
            }
        }
        return attrs;
    }

    static String toXMLString(Element el)
    {
        Transformer transformer;
        try {
            transformer = TransformerFactory.newInstance().newTransformer();
        } catch (TransformerConfigurationException e) {
            throw new RuntimeException(e);
        }
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        StreamResult result = new StreamResult(new StringWriter());
        DOMSource source = new DOMSource(el);
        try {
            transformer.transform(source, result);
        } catch (TransformerException e) {
            throw new RuntimeException(e);
        }

        return result.getWriter().toString();
    }


    private static Date parseDate(String dateStr)
    {
        if (dateStr == null)
            return null;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd");
        try {
            return sdf.parse(dateStr);
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // null or 0 = false, 1 = true
    private static Boolean parseBool(String boolStr)
    {
        if (boolStr == null)
            return null;

        return "1".equals(boolStr);
    }

    private static Integer parseInt(String intStr)
    {
        if (intStr == null)
            return null;

        try {
            return Integer.valueOf(intStr);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Double parseDouble(String doubleStr)
    {
        if (doubleStr == null)
            return null;

        try {
            return Double.parseDouble(doubleStr);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static class SnapGeneDoc
    {
        public DescriptionSegment desc;
        public DNASegment dna;
        public NotesSegment notes;
        public FeaturesSegment features;

        public List<Segment> segments = new ArrayList<>(10);
    }

    abstract static class Segment
    {
    }

    static class UnsupportedSegment extends Segment
    {
        public int type;
        public byte[] data;

        public static UnsupportedSegment parse(int type, byte[] data)
        {
            UnsupportedSegment seg = new UnsupportedSegment();
            seg.type = type;
            seg.data = data;
            return seg;
        }
    }

    static class DescriptionSegment extends Segment
    {
        public static final int TYPE = 9;

        // Either "DNA" or "unknown"
        public final String type;
        public final short exportVersion;
        public final short importVersion;

        public DescriptionSegment(String type, short exportVersion, short importVersion) {
            this.type = type;
            this.exportVersion = exportVersion;
            this.importVersion = importVersion;
        }

        public static DescriptionSegment parse(byte[] data)
        {
            if (data.length != 14)
                throw new IllegalArgumentException("Expected segment length of 14");

            String name = new String(data, 0, 8, Charset.forName("US-ASCII"));
            if (!name.equals("SnapGene"))
                throw new IllegalArgumentException("Expected 'SnapGene' in description segment");

            ByteBuffer bb = ByteBuffer.wrap(data, 8, 6);
            String type = bb.getShort() == 0 ? "unknown" : "DNA";
            short exportVersion = bb.getShort();
            short importVersion = bb.getShort();

            return new DescriptionSegment(type, exportVersion, importVersion);
        }
    }

    static class DNASegment extends Segment
    {
        public static final int TYPE = 0;

        // Either "circular" or "linear"
        public final String topology;
        // Either "double-stranded" or "single-stranded"
        public final String strandedness;
        public final boolean Dam;
        public final boolean Dcm;
        public final boolean EcoKI;
        public final String sequence;

        public DNASegment(String topology, String strandedness, boolean Dam, boolean Dcm, boolean EcoKI, String sequence) {
            this.topology = topology;
            this.strandedness = strandedness;
            this.Dam = Dam;
            this.Dcm = Dcm;
            this.EcoKI = EcoKI;
            this.sequence = sequence;
        }

        public static DNASegment parse(byte[] data)
        {
            byte flags = data[0];
            String sequence = new String(data, 1, data.length-1, Charset.forName("US-ASCII"));

            BitSet bs = BitSet.valueOf(new byte[] { flags });
            String topology = bs.get(0) ? "circular" : "linear";
            String strandedness = bs.get(1) ? "double-stranded" : "single-stranded";
            boolean Dam = bs.get(2);
            boolean Dcm = bs.get(3);
            boolean EcoKI = bs.get(4);

            return new DNASegment(topology, strandedness, Dam, Dcm, EcoKI, sequence);
        }
    }

    static class Reference
    {
        public final String title;
        public final String pubMedID;
        public final String journal;
        public final String authors;

        public Reference(String title, String pubMedID, String journal, String authors) {
            this.title = title;
            this.pubMedID = pubMedID;
            this.journal = journal;
            this.authors = authors;
        }
    }

    static class NotesSegment extends Segment
    {
        public static final int TYPE = 6;

        public final String uuid;
        // either "Natural" or "Synthetic"
        public final String type;
        public final Boolean confirmedExperimentally;
        // HTML-encoded
        public final String description;
        public final Date created;
        public final Date modified;
        // corresponds to Sequence Author
        public final String createdBy;
        // ASCII
        public final String accessionNumber;
        // ASCII
        public final String codeNumber;
        public final String organism;
        public final String sequenceClass;
        public final String transformedInto;
        public final String customMapLabel;
        public final Boolean useCustomMapLabel;
        // HTML-encoded
        public final String comments;
        public final List<Reference> references;

        public NotesSegment(String uuid,
                            String type, Boolean confirmedExperimentally, String description,
                            Date created, Date modified, String createdBy,
                            String accessionNumber, String codeNumber,
                            String organism, String sequenceClass,
                            String transformedInto,
                            String customMapLabel, Boolean useCustomMapLabel,
                            String comments,
                            List<Reference> references)
        {
            this.uuid = uuid;
            this.type = type;
            this.confirmedExperimentally = confirmedExperimentally;
            this.description = description;
            this.created = created;
            this.modified = modified;
            this.createdBy = createdBy;
            this.accessionNumber = accessionNumber;
            this.codeNumber = codeNumber;
            this.organism = organism;
            this.sequenceClass = sequenceClass;
            this.transformedInto = transformedInto;
            this.customMapLabel = customMapLabel;
            this.useCustomMapLabel = useCustomMapLabel;
            this.comments = comments;
            this.references = references;
        }

        public static NotesSegment parse(byte[] data)
        {
            String s = new String(data, Charset.forName("UTF-8"));
            return parse(createDocument(s));
        }

        private static NotesSegment parse(Document doc)
        {
            Element docEl = doc.getDocumentElement();
            if (!"Notes".equals(docEl.getTagName()))
                throw new IllegalArgumentException("Expected 'Notes' element");

            String uuid = getElementInnerText(docEl, "UUID");
            String type = getElementInnerText(docEl, "Type");
            Boolean confirmedExperimentally = parseBool(getElementInnerText(docEl, "ConfirmedExperimentally"));
            String description = getElementInnerText(docEl, "Description");
            Date created = parseDate(getElementInnerText(docEl, "Created"));
            Date modified = parseDate(getElementInnerText(docEl, "LastModified"));
            String createdBy = getElementInnerText(docEl, "CreatedBy");
            String accessionNumber = getElementInnerText(docEl, "AccessionNumber");
            String codeNumber = getElementInnerText(docEl, "CodeNumber");
            String organism = getElementInnerText(docEl, "Organism");
            String sequenceClass = getElementInnerText(docEl, "SequenceClass");
            String transformedInto = getElementInnerText(docEl, "TransformedInto");
            String customMapLabel = getElementInnerText(docEl, "CustomMapLabel");
            Boolean useCustomMapLabel = parseBool(getElementInnerText(docEl, "CustomMapLabel"));
            String comments = getElementInnerText(docEl, "Comments");

            List<Reference> references = new ArrayList<>(4);
            Element refsEl = getElementByTagName(docEl, "References");
            List<Element> refEls = refsEl != null ? getElementsByTagName(refsEl, "Reference") : Collections.emptyList();
            for (Element refEl : refEls)
            {
                String title = getAttribute(refEl, "title");
                String pubMedID = getAttribute(refEl, "pubMedID");
                String journal = getAttribute(refEl, "journal");
                String authors = getAttribute(refEl, "authors");
                references.add(new Reference(title, pubMedID, journal, authors));
            }

            return new NotesSegment(uuid, type, confirmedExperimentally, description, created, modified, createdBy, accessionNumber, codeNumber, organism, sequenceClass, transformedInto, customMapLabel, useCustomMapLabel, comments, references);
        }

    }

    enum Directionality
    {
        NonDirectional,     // = 0
        Forward,            // = 1
        ReverseDirectional, // = 2
        BiDirectional;      // = 3

        public static Directionality fromInt(Integer ordinal)
        {
            if (ordinal == null)
                return null;

            switch (ordinal.intValue())
            {
                case 0: return NonDirectional;
                case 1: return Forward;
                case 2: return ReverseDirectional;
                case 3: return BiDirectional;
                default:
                    throw new IllegalArgumentException("Directionality ordinal out of range: " + ordinal);
            }
        }
    }

    static class Seg
    {
        public final String name; // not used often, it seems
        public final String range; // unparsed range
        public final Integer start;
        public final Integer end;
        public final String color; // hex
        // One of "standard", "gap"
        public final String type;
        public final Boolean translated;
        public final Integer translationNumberingStartsFrom;

        public Seg(String name, String range, Integer start, Integer end, String color, String type, Boolean translated, Integer translationNumberingStartsFrom) {
            this.name = name;
            this.range = range;
            this.start = start;
            this.end = end;
            this.color = color;
            this.type = type;
            this.translated = translated;
            this.translationNumberingStartsFrom = translationNumberingStartsFrom;
        }

        private static Set<String> knownAttrs = new HashSet<>(Arrays.asList(
                "name",
                "range",
                "color",
                "type",
                "translated",
                "translationNumberingStartsFrom"
        ));

        public static Seg parse(Element segmentEl)
        {
            String name = getAttribute(segmentEl, "name");
            Integer start = null;
            Integer end = null;
            String range = getAttribute(segmentEl, "range");
            if (range != null) {
                String[] parts = range.split("-", 2);
                if (parts.length == 2) {
                    start = parseInt(parts[0]);
                    end = parseInt(parts[1]);
                }
            }

            String color = getAttribute(segmentEl, "color");
            String type = getAttribute(segmentEl, "type");
            Boolean translated = parseBool(getAttribute(segmentEl, "translated"));
            Integer translationNumberingStartsFrom = parseInt(getAttribute(segmentEl, "translationNumberingStartsFrom"));

            Set<String> unsupportedAttribute = getOtherAttributeNames(segmentEl, knownAttrs);
            if (!unsupportedAttribute.isEmpty())
                throw new IllegalArgumentException("Unsupported attributes: " + unsupportedAttribute.stream().collect(Collectors.joining(", ")) + "\n" + toXMLString(segmentEl));

            return new Seg(name, range, start, end, color, type, translated, translationNumberingStartsFrom);
        }
    }

    static class Feature
    {
        public final String name;
        public final String type; // genbank type
        public final Directionality directionality;
        public final String geneticCode;
        public final Boolean translateFirstCodonAsMet;
        public final Boolean allowSegmentOverlaps;
        public final Boolean consecutiveTranslationNumbering;
        public final Boolean swappedSegmentNumbering;
        public final Boolean hitsStopCodon;
        public final Double translationMW;
        public final List<Integer> cleavageArrows;
        public final Integer readingFrame;
        public final Boolean visible;

        public final List<Seg> segments;
        public final List<Map.Entry<String, ?>> qualifiers;

        public Feature(String name, Directionality directionality, String geneticCode,
                       Boolean translateFirstCodonAsMet, Boolean allowSegmentOverlaps, Boolean consecutiveTranslationNumbering, Boolean swappedSegmentNumbering, Boolean hitsStopCodon,
                       Double translationMW, String type, List<Integer> cleavageArrows,
                       Integer readingFrame, Boolean visible,
                       List<Seg> segments, List<Map.Entry<String, ?>> qualifiers)
        {
            this.name = name;
            this.directionality = directionality;
            this.geneticCode = geneticCode;
            this.translateFirstCodonAsMet = translateFirstCodonAsMet;
            this.allowSegmentOverlaps = allowSegmentOverlaps;
            this.consecutiveTranslationNumbering = consecutiveTranslationNumbering;
            this.swappedSegmentNumbering = swappedSegmentNumbering;
            this.hitsStopCodon = hitsStopCodon;
            this.translationMW = translationMW;
            this.type = type;
            this.cleavageArrows = cleavageArrows;
            this.readingFrame = readingFrame;
            this.visible = visible;
            this.segments = segments;
            this.qualifiers = qualifiers;
        }

        private static final Set<String> parsedAttrs = new HashSet<>(Arrays.asList(
                "name",
                "type",
                "directionality",
                "geneticCode",
                "translateFirstCodonAsMet",
                "allowSegmentOverlaps",
                "consecutiveTranslationNumbering",
                "swappedSegmentNumbering",
                "hitsStopCodon",
                "translationMW",
                "cleavageArrows",
                "readingFrame",
                "visible"
        ));

        private static final Set<String> ignoredAttrs = new HashSet<>(Arrays.asList(
                "recentID",
                "prioritize",
                "maxRunOn",
                "maxFusedRunOn",
                "consecutiveNumberingStartsFrom",
                "detectionMode" // = "exactProteinMatch"
        ));

        private static final Set<String> knownAttrs;
        static {
            knownAttrs = new HashSet<>();
            knownAttrs.addAll(parsedAttrs);
            knownAttrs.addAll(ignoredAttrs);
        }

        public static Feature parse(Element featureEl)
        {
            String name = getAttribute(featureEl, "name");
            String type = getAttribute(featureEl, "type");
            Directionality directionality = Directionality.fromInt(parseInt(getAttribute(featureEl, "directionality")));
            String geneticCode = getAttribute(featureEl, "geneticCode");
            Boolean translateFirstCodonAsMet = parseBool(getAttribute(featureEl, "translateFirstCodonAsMet"));
            Boolean allowSegmentOverlaps = parseBool(getAttribute(featureEl, "allowSegmentOverlaps"));
            Boolean consecutiveTranslationNumbering = parseBool(getAttribute(featureEl, "consecutiveTranslationNumbering"));
            Boolean swappedSegmentNumbering = parseBool(getAttribute(featureEl, "swappedSegmentNumbering"));
            Boolean hitsStopCodon = parseBool(getAttribute(featureEl, "hitsStopCodon"));
            Double translationMW = parseDouble(getAttribute(featureEl, "translationMW"));

            String cleavageArrowsStr = getAttribute(featureEl, "cleavageArrows");
            List<Integer> cleavageArrows = Collections.emptyList();
            if (cleavageArrowsStr != null)
            {
                cleavageArrows = Arrays.stream(cleavageArrowsStr.split(",")).map(SnapGene::parseInt).collect(Collectors.toList());
            }

            Integer readingFrame = parseInt(getAttribute(featureEl, "readingFrame"));
            Boolean visible = parseBool(getAttribute(featureEl, "visible"));

            Set<String> unsupportedAttribute = getOtherAttributeNames(featureEl, knownAttrs);
            if (!unsupportedAttribute.isEmpty())
                throw new IllegalArgumentException("Unsupported attributes: " + unsupportedAttribute.stream().collect(Collectors.joining(", ")) + "\n" + toXMLString(featureEl));

            List<Seg> segments = new ArrayList<>(4);
            for (Element segmentEl : getElementsByTagName(featureEl, "Segment"))
            {
                segments.add(Seg.parse(segmentEl));
            }

            List<Map.Entry<String, ?>> qualifiers = new ArrayList<>(10);
            for (Element qualifierEl : getElementsByTagName(featureEl, "Q"))
            {
                String key = getAttribute(qualifierEl, "name");
                Element vEl = getElementByTagName(qualifierEl, "V");
                if (vEl == null)
                    throw new IllegalArgumentException("Expected value element");

                Object value;
                if (!vEl.hasAttributes())
                    value = null;
                else if (vEl.hasAttribute("int"))
                    value = parseInt(getAttribute(vEl, "int"));
                else if (vEl.hasAttribute("bool"))
                    value = parseBool(getAttribute(vEl, "bool"));
                else if (vEl.hasAttribute("text"))
                    value = getAttribute(vEl, "text");
                else if (vEl.hasAttribute("predef"))
                    value = getAttribute(vEl, "predef");
                else
                    throw new IllegalArgumentException("Unsupported value type for '" + key + "':" + toXMLString(vEl));

                qualifiers.add(new AbstractMap.SimpleEntry<>(key, value));
            }

            return new Feature(name, directionality, geneticCode, translateFirstCodonAsMet, allowSegmentOverlaps, consecutiveTranslationNumbering, swappedSegmentNumbering, hitsStopCodon, translationMW, type, cleavageArrows, readingFrame, visible, segments, qualifiers);
        }

    }

    static class FeaturesSegment extends Segment
    {
        public final List<Feature> features;

        public FeaturesSegment(List<Feature> features) {
            this.features = features;
        }

        public static FeaturesSegment parse(byte[] data)
        {
            String s = new String(data, Charset.forName("UTF-8"));
            return parse(createDocument(s));
        }

        public static FeaturesSegment parse(Document doc)
        {
            Element docEl = doc.getDocumentElement();
            if (!"Features".equals(docEl.getTagName()))
                throw new IllegalArgumentException("Expected 'Features' element");

            List<Feature> features = new ArrayList<>(100);
            for (Element featureEl : getElementsByTagName(docEl, "Feature"))
            {
                features.add(Feature.parse(featureEl));
            }

            return new FeaturesSegment(features);
        }
    }
}


