package com.opensmpsdeck.io;

import com.opensmpsdeck.model.FmVoice;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Imports FM voice data from RYM2612 (.rym2612) XML patch files.
 *
 * <p>RYM2612 is a VSTi FM synthesizer that stores patches as XML with floating-point
 * parameter values. This importer converts those values to SMPS-compatible 25-byte
 * FM voice data.</p>
 */
public final class Rym2612Importer {

    private static final double MUL_DIVISOR = 66.6;

    private Rym2612Importer() {
        // Utility class
    }

    /**
     * Parse a .rym2612 XML file and return an {@link FmVoice}.
     *
     * @param file the .rym2612 file to import
     * @return the parsed FM voice
     * @throws IOException if the file cannot be read or parsed
     */
    public static FmVoice importFile(File file) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Document doc = builder.parse(file);
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();
            if (!"RYM2612Params".equals(root.getTagName())) {
                throw new IOException(
                        "Invalid RYM2612 file: expected root element 'RYM2612Params' but found '"
                                + root.getTagName() + "'");
            }
            String patchName = root.getAttribute("patchName");

            // Collect all PARAM elements into a map
            Map<String, Double> params = new HashMap<>();
            NodeList paramNodes = root.getElementsByTagName("PARAM");
            for (int i = 0; i < paramNodes.getLength(); i++) {
                Element param = (Element) paramNodes.item(i);
                String id = param.getAttribute("id");
                String valueStr = param.getAttribute("value");
                if (!valueStr.isEmpty()) {
                    params.put(id, Double.parseDouble(valueStr));
                }
            }

            int algorithm = clamp(round(params.getOrDefault("Algorithm", 0.0)), 0, 7);
            int feedback = clamp(round(params.getOrDefault("Feedback", 0.0)), 0, 7);

            byte[] data = new byte[FmVoice.VOICE_SIZE];
            data[0] = (byte) ((feedback << 3) | (algorithm & 0x07));

            // Parse each display-order operator (OP1-OP4) and write to SMPS operator slot
            for (int displayOp = 0; displayOp < FmVoice.OPERATOR_COUNT; displayOp++) {
                int smpsOp = FmVoice.displayToSmps(displayOp);
                String prefix = "OP" + (displayOp + 1);

                int mul = clamp(round(params.getOrDefault(prefix + "MUL", 0.0) / MUL_DIVISOR), 0, 15);
                int dt = convertDetune(round(params.getOrDefault(prefix + "DT", 0.0)));
                int tl = clamp(round(params.getOrDefault(prefix + "TL", 0.0)), 0, 127);
                int rs = clamp(round(params.getOrDefault(prefix + "RS", 0.0)), 0, 3);
                int ar = clamp(round(params.getOrDefault(prefix + "AR", 0.0)), 0, 31);
                int am = clamp(round(params.getOrDefault(prefix + "AM", 0.0)), 0, 1);
                int d1r = clamp(round(params.getOrDefault(prefix + "D1R", 0.0)), 0, 31);
                int d2r = clamp(round(params.getOrDefault(prefix + "D2R", 0.0)), 0, 31);
                int d1l = clamp(round(params.getOrDefault(prefix + "D2L", 0.0)), 0, 15); // RYM D2L = SMPS D1L
                int rr = clamp(round(params.getOrDefault(prefix + "RR", 0.0)), 0, 15);

                int base = 1 + smpsOp * FmVoice.PARAMS_PER_OPERATOR;
                data[base]     = (byte) ((dt << 4) | (mul & 0x0F));       // DT_MUL
                data[base + 1] = (byte) (tl & 0x7F);                      // TL
                data[base + 2] = (byte) ((rs << 6) | (ar & 0x1F));        // RS_AR
                data[base + 3] = (byte) ((am << 7) | (d1r & 0x1F));       // AM_D1R
                data[base + 4] = (byte) (d2r & 0x1F);                     // D2R
                data[base + 5] = (byte) ((d1l << 4) | (rr & 0x0F));       // D1L_RR
            }

            return new FmVoice(patchName, data);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse RYM2612 file: " + file.getName(), e);
        }
    }

    /**
     * Convert signed detune value (-3..3) to YM2612 register format (0..7).
     * Negative values: -1 -> 5, -2 -> 6, -3 -> 7 (bit 2 set + absolute value).
     * Non-negative values: 0..3 map directly.
     */
    private static int convertDetune(int dt) {
        if (dt < 0) {
            return 4 + Math.min(Math.abs(dt), 3);
        }
        return Math.min(dt, 3);
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
