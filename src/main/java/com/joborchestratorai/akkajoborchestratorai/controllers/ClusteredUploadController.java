package com.joborchestratorai.akkajoborchestratorai.controllers;

import akka.actor.typed.ActorSystem;
import com.joborchestratorai.akkajoborchestratorai.actors.ClusteredMasterActor;
import com.joborchestratorai.akkajoborchestratorai.services.ClusteredResumeSearchService;
import com.joborchestratorai.akkajoborchestratorai.services.LocalStorageService;
import com.joborchestratorai.akkajoborchestratorai.services.EventSourcingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
@Profile({"node1", "node2", "node3", "node4", "clustered"})
public class ClusteredUploadController {

    @Autowired
    private ClusteredResumeSearchService clusteredService;
    
    @Autowired
    private LocalStorageService localStorageService;
    
    @Autowired
    private EventSourcingService eventSourcingService;

    private static final Pattern COMPANY_DOMAIN_PATTERN = Pattern.compile("([a-zA-Z0-9]+)[-_\\s]*resumes?", Pattern.CASE_INSENSITIVE);

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "baseResume", required = false) MultipartFile baseResume) {
        
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "error", "Please select a file to upload"
                ));
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null ||
                    (!originalFilename.endsWith(".xlsx") && !originalFilename.endsWith(".xls") && 
                     !originalFilename.endsWith(".pdf") && !originalFilename.endsWith(".docx"))) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "error", "Please upload a valid resume file (.xlsx, .xls, .pdf, or .docx)"
                ));
            }

            // Extract company domain for smart sharding
            String companyDomain = extractCompanyDomain(originalFilename);
            String datasetId = generateDatasetId(companyDomain);

            // Save file temporarily for processing
            String fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
            Path tempFile = Files.createTempFile("resume-cluster-", fileExtension);
            file.transferTo(tempFile.toFile());

            // Process file based on format and extract data
            Set<String> tags = new LinkedHashSet<>();
            List<String> bullets = new ArrayList<>();
            List<com.joborchestratorai.akkajoborchestratorai.models.ResumeRow> rowsOut = new ArrayList<>();
            
            // Extract text content based on file format
            String masterResumeText = extractTextFromFile(tempFile.toFile(), originalFilename);
            
            // Convert text to bullet points (split by lines/paragraphs)
            if (masterResumeText != null && !masterResumeText.trim().isEmpty()) {
                String[] lines = masterResumeText.split("[\\r\\n]+");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && trimmed.length() > 10) { // Filter meaningful content
                        bullets.add(trimmed);
                        // Create row without tags (simplified approach)
                        rowsOut.add(new com.joborchestratorai.akkajoborchestratorai.models.ResumeRow(trimmed, List.of()));
                    }
                }
            }

            // Process everything in parallel - store local data immediately
            com.joborchestratorai.akkajoborchestratorai.models.ResumeData rd =
                    new com.joborchestratorai.akkajoborchestratorai.models.ResumeData(datasetId, originalFilename, bullets);
            localStorageService.storeDatasetResumeData(datasetId, rd);
            localStorageService.saveLatestResumeRows(rowsOut);

            // Start parallel operations with reduced timeouts
            String resumeContent = String.join("\n", bullets);
            
            // Parallel PDF processing
            CompletableFuture<Void> pdfProcessing = CompletableFuture.runAsync(() -> {
                if (baseResume != null && !baseResume.isEmpty()) {
                    try {
                        String name = baseResume.getOriginalFilename();
                        if (name != null && name.toLowerCase().endsWith(".pdf")) {
                            byte[] pdfBytes = baseResume.getBytes();
                            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
                                org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                                String txt = stripper.getText(doc);
                                localStorageService.saveBaseResumeText(txt);
                            }
                        }
                    } catch (Exception pdfEx) {
                        System.err.println("Warning: failed to parse base resume PDF: " + pdfEx.getMessage());
                    }
                }
            });

            // Parallel cluster indexing with fast timeout
            CompletableFuture<Void> clusterIndexing = clusteredService.indexDatasetFile(datasetId, tempFile.toString())
                .orTimeout(8, TimeUnit.SECONDS)
                .exceptionally(throwable -> {
                    System.err.println("Cluster indexing failed: " + throwable.getMessage());
                    return null;
                });

            // Parallel event sourcing with fast timeout
            CompletableFuture<EventSourcingService.ResumeProcessingResult> eventSourcingFuture = 
                eventSourcingService.processResumeWithEventSourcing(datasetId, resumeContent, companyDomain)
                .orTimeout(8, TimeUnit.SECONDS)
                .exceptionally(throwable -> {
                    System.err.println("Event sourcing failed: " + throwable.getMessage());
                    return new EventSourcingService.ResumeProcessingResult(
                        datasetId, false, "Event sourcing failed: " + throwable.getMessage(), 
                        companyDomain, System.currentTimeMillis()
                    );
                });

            // Wait for operations with overall fast timeout - don't block UI
            EventSourcingService.ResumeProcessingResult eventResult = null;
            try {
                CompletableFuture.allOf(pdfProcessing, clusterIndexing, eventSourcingFuture).get(10, TimeUnit.SECONDS);
                eventResult = eventSourcingFuture.get();
            } catch (Exception e) {
                System.err.println("Background operations timeout (non-blocking): " + e.getMessage());
                // Continue with response - background operations can complete later
                eventResult = new EventSourcingService.ResumeProcessingResult(
                    datasetId, false, "Background processing", companyDomain, System.currentTimeMillis()
                );
            }

            // Clean up temp file
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception e) {
                // Non-critical cleanup failure
            }

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Resume data uploaded and distributed across cluster with event sourcing");
            response.put("filename", originalFilename);
            response.put("status", "success");
            response.put("datasetId", datasetId);
            response.put("companyDomain", companyDomain);
            response.put("detectedTags", new ArrayList<>(tags));
            response.put("clusterDistributed", true);
            response.put("eventSourcing", Map.of(
                "enabled", true,
                "success", eventResult != null ? eventResult.success : false,
                "message", eventResult != null ? eventResult.message : "Event sourcing failed"
            ));
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "error",
                    "error", "Failed to process file in cluster: " + e.getMessage()
            ));
        }
    }

    private String extractCompanyDomain(String filename) {
        if (filename == null) return "default";
        
        // Try to extract company name from filename patterns like "google-resumes.xlsx" or "microsoft_resumes.xlsx"
        java.util.regex.Matcher matcher = COMPANY_DOMAIN_PATTERN.matcher(filename);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase();
        }
        
        // Fallback: use first part of filename before any delimiter
        String baseName = filename.toLowerCase()
                .replaceAll("\\.(xlsx|xls)$", "")
                .replaceAll("[^a-z0-9]", "-");
        
        String[] parts = baseName.split("-");
        return parts.length > 0 ? parts[0] : "default";
    }

    private String generateDatasetId(String companyDomain) {
        return companyDomain + "-resumes-" + System.currentTimeMillis();
    }
    
    private String extractTextFromFile(java.io.File file, String filename) {
        String lowerFilename = filename.toLowerCase();
        try {
            if (lowerFilename.endsWith(".pdf")) {
                return extractPdfText(file);
            } else if (lowerFilename.endsWith(".docx")) {
                return extractDocxText(file);
            } else if (lowerFilename.endsWith(".xlsx") || lowerFilename.endsWith(".xls")) {
                return extractExcelText(file);
            }
        } catch (Exception e) {
            System.err.println("Error extracting text from " + filename + ": " + e.getMessage());
        }
        return "";
    }
    
    private String extractPdfText(java.io.File file) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(file)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            return stripper.getText(document);
        }
    }
    
    private String extractDocxText(java.io.File file) throws Exception {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument(fis)) {
            
            StringBuilder text = new StringBuilder();
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append("\n");
            }
            
            // Extract text from tables too
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : document.getTables()) {
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        text.append(cell.getText()).append(" ");
                    }
                    text.append("\n");
                }
            }
            
            return text.toString();
        }
    }
    
    private String extractExcelText(java.io.File file) throws Exception {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(fis)) {
            
            StringBuilder text = new StringBuilder();
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                    if (cell != null) {
                        switch (cell.getCellType()) {
                            case STRING:
                                text.append(cell.getStringCellValue()).append(" ");
                                break;
                            case NUMERIC:
                                text.append(cell.getNumericCellValue()).append(" ");
                                break;
                            default:
                                break;
                        }
                    }
                }
                text.append("\n");
            }
            
            return text.toString();
        }
    }
}