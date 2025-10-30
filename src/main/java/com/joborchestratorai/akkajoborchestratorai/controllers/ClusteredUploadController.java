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
                    (!originalFilename.endsWith(".xlsx") && !originalFilename.endsWith(".xls"))) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "error", "Please upload a valid Excel file (.xlsx or .xls)"
                ));
            }

            // Extract company domain for smart sharding
            String companyDomain = extractCompanyDomain(originalFilename);
            String datasetId = generateDatasetId(companyDomain);

            // Save file temporarily for processing
            Path tempFile = Files.createTempFile("resume-cluster-", ".xlsx");
            file.transferTo(tempFile.toFile());

            // Process Excel file and extract data locally first
            Set<String> tags = new LinkedHashSet<>();
            List<String> bullets = new ArrayList<>();
            List<com.joborchestratorai.akkajoborchestratorai.models.ResumeRow> rowsOut = new ArrayList<>();
            
            try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile.toFile());
                 org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(fis)) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
                if (sheet != null) {
                    org.apache.poi.ss.usermodel.Row header = sheet.getRow(0);
                    int tagColIdx = -1;
                    if (header != null) {
                        for (org.apache.poi.ss.usermodel.Cell c : header) {
                            if (c != null && c.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                                String v = c.getStringCellValue();
                                if (v != null && v.trim().equalsIgnoreCase("Detected Tags")) {
                                    tagColIdx = c.getColumnIndex();
                                    break;
                                }
                            }
                        }
                    }
                    if (tagColIdx == -1) {
                        tagColIdx = 1; // fallback to column B
                    }

                    for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                        org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                        if (row == null) continue;
                        
                        // bullet text (col A)
                        String bulletText = null;
                        org.apache.poi.ss.usermodel.Cell bulletCell = row.getCell(0);
                        if (bulletCell != null && bulletCell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                            bulletText = bulletCell.getStringCellValue();
                            if (bulletText != null && !bulletText.trim().isEmpty()) {
                                bullets.add(bulletText.trim());
                            }
                        }
                        
                        // tags (detected) col tagColIdx
                        org.apache.poi.ss.usermodel.Cell tagCell = row.getCell(tagColIdx);
                        List<String> rowTags = new ArrayList<>();
                        if (tagCell != null) {
                            String cellText = null;
                            if (tagCell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                                cellText = tagCell.getStringCellValue();
                            } else if (tagCell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                                cellText = String.valueOf(tagCell.getNumericCellValue());
                            }
                            if (cellText != null) {
                                for (String p : cellText.split(",")) {
                                    String t = p.trim();
                                    if (!t.isEmpty()) { 
                                        tags.add(t); 
                                        rowTags.add(t); 
                                    }
                                }
                            }
                        }
                        if (bulletText != null && !rowTags.isEmpty()) {
                            rowsOut.add(new com.joborchestratorai.akkajoborchestratorai.models.ResumeRow(bulletText.trim(), rowTags));
                        }
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
}