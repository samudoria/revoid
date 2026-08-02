// Ghidra headless script: ExportNativeAnalysis.java
// Exports function list with disassembly and decompiled pseudocode as JSON.
// Usage: analyzeHeadless ... -postScript ExportNativeAnalysis.java <output.json>
//
// @category Analysis
// @author jadx-native-viewer

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.File;
import java.io.FileWriter;

public class ExportNativeAnalysis extends GhidraScript {

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) {
            printerr("Usage: ExportNativeAnalysis.java <output_json_path>");
            return;
        }
        File outputFile = new File(args[0]);

        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        ConsoleTaskMonitor taskMonitor = new ConsoleTaskMonitor();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"functions\": [\n");

        FunctionIterator iter = currentProgram.getFunctionManager().getFunctions(true);
        boolean first = true;

        for (Function func : iter) {
            if (monitor.isCancelled()) break;
            
            // We want to include external/thunk functions so they appear in xrefs
            // but we skip full decompilation/disassembly for them.
            boolean isExternalOrThunk = func.isExternal() || func.isThunk();

            if (!first) json.append(",\n");
            first = false;

            String name = escapeJson(func.getName());
            long address = func.getEntryPoint().getOffset();
            long size = func.getBody() != null ? func.getBody().getNumAddresses() : 0;

            // Disassembly
            StringBuilder disasm = new StringBuilder();
            if (!isExternalOrThunk && func.getBody() != null) {
                Listing listing = currentProgram.getListing();
                CodeUnitIterator cuIter = listing.getCodeUnits(func.getBody(), true);
                while (cuIter.hasNext()) {
                    CodeUnit cu = cuIter.next();
                    disasm.append(String.format("0x%08X:  %s\n",
                        cu.getAddress().getOffset(), cu.toString()));
                }
            }

            // Decompile
            String decompiledCode = "";
            if (!isExternalOrThunk) {
                try {
                    DecompileResults results = decomp.decompileFunction(func, 30, taskMonitor);
                    if (results.decompileCompleted()) {
                        decompiledCode = results.getDecompiledFunction().getC();
                    } else {
                        decompiledCode = "// Decompilation failed: " + results.getErrorMessage();
                    }
                } catch (Exception e) {
                    decompiledCode = "// Decompilation error: " + e.getMessage();
                }
            } else {
                decompiledCode = "// External or Thunk function";
            }

            json.append("  {\n");
            json.append("    \"name\": \"").append(name).append("\",\n");
            json.append("    \"address\": ").append(address).append(",\n");
            json.append("    \"size\": ").append(size).append(",\n");
            json.append("    \"disassembly\": \"").append(escapeJson(disasm.toString())).append("\",\n");
            json.append("    \"decompiled\": \"").append(escapeJson(decompiledCode)).append("\",\n");

            // Extract outgoing references
            json.append("    \"outgoing\": [\n");
            ghidra.program.model.symbol.ReferenceManager refMgr = currentProgram.getReferenceManager();
            InstructionIterator instIter = currentProgram.getListing().getInstructions(func.getBody(), true);
            boolean firstOut = true;
            while(instIter.hasNext()) {
                Instruction inst = instIter.next();
                ghidra.program.model.symbol.Reference[] refs = inst.getReferencesFrom();
                for (ghidra.program.model.symbol.Reference ref : refs) {
                    if (!ref.isMemoryReference()) continue;
                    String refType = "DATA";
                    if (ref.getReferenceType().isCall()) refType = "CALL";
                    else if (ref.getReferenceType().isJump()) refType = "JUMP";
                    
                    if (!firstOut) json.append(",\n");
                    firstOut = false;
                    json.append("      {\n");
                    json.append("        \"target\": ").append(ref.getToAddress().getOffset()).append(",\n");
                    json.append("        \"type\": \"").append(refType).append("\"\n");
                    json.append("      }");
                }
            }
            json.append("\n    ],\n");

            // Incoming references
            json.append("    \"incoming\": [\n");
            ghidra.program.model.symbol.ReferenceIterator incIter = refMgr.getReferencesTo(func.getEntryPoint());
            boolean firstInc = true;
            while(incIter.hasNext()) {
                ghidra.program.model.symbol.Reference ref = incIter.next();
                String refType = "DATA";
                if (ref.getReferenceType().isCall()) refType = "CALL";
                else if (ref.getReferenceType().isJump()) refType = "JUMP";

                if (!firstInc) json.append(",\n");
                firstInc = false;
                
                Function callerFunc = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                long sourceOffset = callerFunc != null ? callerFunc.getEntryPoint().getOffset() : ref.getFromAddress().getOffset();
                
                json.append("      {\n");
                json.append("        \"source\": ").append(sourceOffset).append(",\n");
                json.append("        \"type\": \"").append(refType).append("\"\n");
                json.append("      }");
            }
            json.append("\n    ]\n");
            json.append("  }");
        }

        json.append("\n  ],\n");

        // Extract Strings
        json.append("  \"strings\": [\n");
        ghidra.program.model.listing.DataIterator dataIter = currentProgram.getListing().getDefinedData(true);
        boolean firstString = true;
        while (dataIter.hasNext()) {
            ghidra.program.model.listing.Data data = dataIter.next();
            if (!data.hasStringValue()) continue;
            if (!firstString) json.append(",\n");
            firstString = false;
            
            Object val = data.getValue();
            String strVal = val != null ? val.toString() : data.getDefaultValueRepresentation();
            int len = data.getLength();
            int refCount = currentProgram.getReferenceManager().getReferenceCountTo(data.getAddress());

            json.append("    {\n");
            json.append("      \"address\": ").append(data.getAddress().getOffset()).append(",\n");
            json.append("      \"stringValue\": \"").append(escapeJson(strVal)).append("\",\n");
            json.append("      \"length\": ").append(len).append(",\n");
            json.append("      \"referenceCount\": ").append(refCount).append("\n");
            json.append("    }");
        }
        json.append("\n  ],\n");

        // Extract Imports
        json.append("  \"imports\": [\n");
        ghidra.program.model.symbol.SymbolIterator symIter = currentProgram.getSymbolTable().getExternalSymbols();
        boolean firstImport = true;
        for (ghidra.program.model.symbol.Symbol s : symIter) {
            if (!firstImport) json.append(",\n");
            firstImport = false;
            json.append("    {\n");
            json.append("      \"name\": \"").append(escapeJson(s.getName())).append("\",\n");
            json.append("      \"address\": ").append(s.getAddress().getOffset()).append("\n");
            json.append("    }");
        }
        json.append("\n  ],\n");

        // Extract Exports
        json.append("  \"exports\": [\n");
        ghidra.program.model.symbol.SymbolIterator exportIter = currentProgram.getSymbolTable().getSymbolIterator(true);
        boolean firstExport = true;
        for (ghidra.program.model.symbol.Symbol s : exportIter) {
            if (s.isExternalEntryPoint()) {
                if (!firstExport) json.append(",\n");
                firstExport = false;
                json.append("    {\n");
                json.append("      \"name\": \"").append(escapeJson(s.getName())).append("\",\n");
                json.append("      \"address\": ").append(s.getAddress().getOffset()).append("\n");
                json.append("    }");
            }
        }
        json.append("\n  ],\n");

        // Extract Globals
        json.append("  \"globals\": [\n");
        ghidra.program.model.symbol.SymbolIterator globalIter = currentProgram.getSymbolTable().getSymbolIterator(true);
        boolean firstGlobal = true;
        for (ghidra.program.model.symbol.Symbol s : globalIter) {
            if (s.isGlobal() && s.getSymbolType() == ghidra.program.model.symbol.SymbolType.LABEL) {
                ghidra.program.model.listing.Data d = currentProgram.getListing().getDataAt(s.getAddress());
                if (d != null && d.isDefined() && !d.isPointer() && !d.hasStringValue()) {
                    if (!firstGlobal) json.append(",\n");
                    firstGlobal = false;
                    json.append("    {\n");
                    json.append("      \"name\": \"").append(escapeJson(s.getName())).append("\",\n");
                    json.append("      \"address\": ").append(s.getAddress().getOffset()).append(",\n");
                    json.append("      \"size\": ").append(d.getLength()).append(",\n");
                    json.append("      \"dataType\": \"").append(escapeJson(d.getDataType().getName())).append("\"\n");
                    json.append("    }");
                }
            }
        }
        json.append("\n  ]\n");

        json.append("}\n");
        decomp.dispose();

        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(json.toString());
        }
        println("ExportNativeAnalysis: wrote " + outputFile.getAbsolutePath());
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
