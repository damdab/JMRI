/*
    The sole purpose of this class is to create the text file that can be
    copied into a JMRI .xml file to ease the creation of a CTC panel from
    scratch.
 */
package code;

import java.io.PrintWriter;
import java.util.ArrayList;
import jmri.jmrit.ctcserialdata.CodeButtonHandlerData;
import jmri.jmrit.ctcserialdata.OtherData;

/**
 * @author Gregory J. Bedlek Copyright (C) 2018, 2019
 */
public class CreateXMLFiles {
    
    private final OtherData _mOtherData;
    private final ArrayList <CodeButtonHandlerData> _mCodeButtonHandlerDataArrayList;
            
    public CreateXMLFiles(OtherData otherData, ArrayList <CodeButtonHandlerData> codeButtonHandlerDataArrayList) {
        _mOtherData = otherData;
        _mCodeButtonHandlerDataArrayList = codeButtonHandlerDataArrayList;  // Reference copy, not a NEW deep copy!
    }
    
    public void createTextFiles(String directoryToCreateThemIn) {
        CreateInternalSensorsXMLFile.writeInternalSensors(directoryToCreateThemIn, _mOtherData, _mCodeButtonHandlerDataArrayList);
//  SOMEDAY I'll have to deal with _mOtherData._mGUIDesign_CTCPanelType with a switch statement here:
        CreateGUIObjectsXMLFile.writeGUIObjects(directoryToCreateThemIn, _mOtherData, _mCodeButtonHandlerDataArrayList);
        CreateVirtualSignalsXMLFile.writeVirtualSignals(directoryToCreateThemIn, _mOtherData, _mCodeButtonHandlerDataArrayList);
    }
    
    public static void generateProlog(PrintWriter printWriter) {
        printWriter.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        printWriter.println("<?xml-stylesheet href=\"/xml/XSLT/panelfile-2-9-6.xsl\" type=\"text/xsl\"?>");
        printWriter.println("<layout-config xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"http://jmri.org/xml/schema/layout-2-9-6.xsd\">");
    }
    
    public static void generateEpilogue(PrintWriter printWriter) {
        printWriter.println("</layout-config>");
    }
}
