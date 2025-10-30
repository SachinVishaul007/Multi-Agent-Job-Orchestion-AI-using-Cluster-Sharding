package com.joborchestratorai.akkajoborchestratorai.controllers;


import com.joborchestratorai.akkajoborchestratorai.models.SearchRequest;
import com.joborchestratorai.akkajoborchestratorai.models.SearchResult;
import com.joborchestratorai.akkajoborchestratorai.services.ResumeSearchService;
import com.joborchestratorai.akkajoborchestratorai.services.OpenAIService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

@RestController
@RequestMapping("/api")
@CrossOrigin
@Profile("single-node")
public class ResumeController {

    private final ResumeSearchService resumeSearchService;
    private final OpenAIService openAIService;
    private final com.joborchestratorai.akkajoborchestratorai.services.LocalStorageService localStorageService;

    public ResumeController(ResumeSearchService resumeSearchService, OpenAIService openAIService,
                            com.joborchestratorai.akkajoborchestratorai.services.LocalStorageService localStorageService) {
        this.resumeSearchService = resumeSearchService;
        this.openAIService = openAIService;
        this.localStorageService = localStorageService;
    }

    // Upload endpoint
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadExcel(@RequestParam("file") MultipartFile file,
                                                           @RequestParam(value = "baseResume", required = false) MultipartFile baseResume) {
        try {
            // Validate file
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Please select a file to upload"));
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null ||
                    (!originalFilename.endsWith(".xlsx") && !originalFilename.endsWith(".xls"))) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Please upload a valid Excel file (.xlsx or .xls)"));
            }

            Path tempFile = Files.createTempFile("resume-", ".xlsx");
            file.transferTo(tempFile.toFile());

            resumeSearchService.indexExcelFile(tempFile.toString());

            // Optionally parse base resume PDF and store text (non-fatal)
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

            // Parse bullets + "Detected Tags" and return to client
            java.util.Set<String> tags = new java.util.LinkedHashSet<>();
            java.util.List<String> bullets = new java.util.ArrayList<>();
            java.util.List<com.joborchestratorai.akkajoborchestratorai.models.ResumeRow> rowsOut = new java.util.ArrayList<>();
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
                        // Fallback: assume column B (index 1)
                        tagColIdx = 1;
                    }

                    for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                        org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                        if (row == null) continue;
                        // bullet text col A
                        String bulletText = null;
                        org.apache.poi.ss.usermodel.Cell bulletCell = row.getCell(0);
                        if (bulletCell != null && bulletCell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                            bulletText = bulletCell.getStringCellValue();
                            if (bulletText != null && !bulletText.trim().isEmpty()) {
                                bullets.add(bulletText.trim());
                            }
                        }
                        // detected tags
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(tagColIdx);
                        java.util.List<String> rowTags = new java.util.ArrayList<>();
                        if (cell != null) {
                            String cellText = null;
                            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                                cellText = cell.getStringCellValue();
                            } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                                cellText = String.valueOf(cell.getNumericCellValue());
                            }
                            if (cellText != null) {
                                String[] parts = cellText.split(",");
                                for (String p : parts) {
                                    String t = p.trim();
                                    if (!t.isEmpty()) { tags.add(t); rowTags.add(t);} 
                                }
                            }
                        }
                        if (bulletText != null && !rowTags.isEmpty()) {
                            rowsOut.add(new com.joborchestratorai.akkajoborchestratorai.models.ResumeRow(bulletText.trim(), rowTags));
                        }
                    }
                }
            } catch (Exception ignore) {
                // Non-fatal: continue without tags
            }

            // Persist bullets/mapping for single-node profile as well
            try {
                com.joborchestratorai.akkajoborchestratorai.models.ResumeData rd =
                        new com.joborchestratorai.akkajoborchestratorai.models.ResumeData(null, originalFilename, bullets);
                localStorageService.storeResumeData(rd);
                localStorageService.saveLatestResumeRows(rowsOut);
            } catch (Exception e2) {
                System.err.println("Warning: failed to persist resume rows: " + e2.getMessage());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Resume data uploaded and indexed successfully");
            response.put("filename", originalFilename);
            response.put("status", "success");
            response.put("detectedTags", new java.util.ArrayList<>(tags));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to process file: " + e.getMessage());
            error.put("status", "error");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // Generate tailored LaTeX resume
    @PostMapping("/generate-resume")
    public ResponseEntity<Map<String, String>> generateTailoredResume(@RequestBody Map<String, String> request) {
        try {
            String jobDescription = request.get("jobDescription");

            if (jobDescription == null || jobDescription.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Job description is required", "status", "error"));
            }

            String latexResume = openAIService.generateTailoredResume(jobDescription);

            return ResponseEntity.ok(Map.of(
                    "message", "Resume generated successfully",
                    "latexCode", latexResume,
                    "status", "success"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to generate resume: " + e.getMessage(),
                            "status", "error"
                    ));
        }
    }

    // Legacy search for resume points (keeping for compatibility)
    @PostMapping("/search")
    public CompletionStage<ResponseEntity<List<SearchResult>>> search(@RequestBody SearchRequest request) {
        return resumeSearchService.searchResumes(request.getJobDescription(), request.getTopK())
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.internalServerError().build());
    }

    // Health check endpoint
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "healthy", "service", "resume-tailoring"));
    }
}
