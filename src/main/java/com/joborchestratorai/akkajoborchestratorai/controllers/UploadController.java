package com.joborchestratorai.akkajoborchestratorai.controllers;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api")
@Profile("!clustered & !node1 & !node2 & !node3 & !node4")
public class UploadController {
    private final com.joborchestratorai.akkajoborchestratorai.services.LocalStorageService localStorageService;

    public UploadController(com.joborchestratorai.akkajoborchestratorai.services.LocalStorageService localStorageService) {
        this.localStorageService = localStorageService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadExcel(@RequestParam("file") MultipartFile file,
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

            Path tempFile = Files.createTempFile("resume-", ".xlsx");
            file.transferTo(tempFile.toFile());

            // Extract bullets and tags from columns A (Bullet Text) and B (Detected Tags)
            Set<String> tags = new LinkedHashSet<>();
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
                        java.util.List<String> rowTags = new java.util.ArrayList<>();
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
                                    if (!t.isEmpty()) { tags.add(t); rowTags.add(t); }
                                }
                            }
                        }
                        if (bulletText != null && !rowTags.isEmpty()) {
                            rowsOut.add(new com.joborchestratorai.akkajoborchestratorai.models.ResumeRow(bulletText.trim(), rowTags));
                        }
                    }
                }
            } catch (Exception ignore) {
                // ignore tag extraction errors
            }

            // Persist bullets and per-row mapping for matching
            try {
                com.joborchestratorai.akkajoborchestratorai.models.ResumeData rd =
                        new com.joborchestratorai.akkajoborchestratorai.models.ResumeData(null, originalFilename, bullets);
                localStorageService.storeResumeData(rd);
            } catch (Exception e) {
                System.err.println("Warning: failed to persist resume bullets: " + e.getMessage());
            }
            try {
                localStorageService.saveLatestResumeRows(rowsOut);
            } catch (Exception e) {
                System.err.println("Warning: failed to persist resume rows: " + e.getMessage());
            }

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

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Resume data uploaded successfully");
            response.put("filename", originalFilename);
            response.put("status", "success");
            response.put("detectedTags", new ArrayList<>(tags));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "error",
                    "error", "Failed to process file: " + e.getMessage()
            ));
        }
    }
}
