package com.joborchestratorai.akkajoborchestratorai.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.joborchestratorai.akkajoborchestratorai.models.ResumeData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Removed PostConstruct import - using lazy initialization instead
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class LocalStorageService {

    @Value("${resume.storage.path:./resume-data}")
    private String storagePath;

    private final ObjectMapper objectMapper;

    public LocalStorageService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    private String getStoragePath() {
        // Lazy initialization - ensures @Value is injected before first use
        return (storagePath != null && !storagePath.trim().isEmpty())
                ? storagePath : "./resume-data";
    }

    public void storeResumeData(ResumeData resumeData) throws IOException {
        if (resumeData == null) {
            throw new IllegalArgumentException("Resume data cannot be null");
        }

        // Use the helper method instead of direct storagePath access
        Path storageDir = Paths.get(getStoragePath());

        // Generate file ID if null
        String fileId = resumeData.getId();
        if (fileId == null || fileId.trim().isEmpty()) {
            // Generate a unique ID based on timestamp and UUID
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String uniqueId = UUID.randomUUID().toString().substring(0, 8);
            fileId = "resume_" + timestamp + "_" + uniqueId;

            // Set the ID back to the resume data if it has a setter
            try {
                resumeData.setId(fileId);
            } catch (Exception e) {
                // If no setter, we'll just use the generated ID for the filename
                System.out.println("Generated file ID: " + fileId + " (could not set on ResumeData object)");
            }
        }

        Path dataPath = storageDir.resolve(fileId + ".json");
        objectMapper.writeValue(dataPath.toFile(), resumeData);
        System.out.println("Resume data stored successfully at: " + dataPath.toString());
    }

    public ResumeData getLatestResumeData() throws IOException {
        File dir = new File(getStoragePath());

        // Check if directory exists
        if (!dir.exists()) {
            System.out.println("Storage directory does not exist: " + getStoragePath());
            return null;
        }

        // Only consider primary resume snapshots, ignore helper files like latest_rows.json
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json") && name.startsWith("resume_"));

        if (files == null || files.length == 0) {
            System.out.println("No resume data files found in: " + getStoragePath());
            return null;
        }

        File latestFile = files[0];
        for (File file : files) {
            if (file.lastModified() > latestFile.lastModified()) {
                latestFile = file;
            }
        }

        System.out.println("Loading resume data from: " + latestFile.getAbsolutePath());
        return objectMapper.readValue(latestFile, ResumeData.class);
    }

    // Additional utility methods
    public boolean hasResumeData() {
        File dir = new File(getStoragePath());
        if (!dir.exists()) {
            return false;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        return files != null && files.length > 0;
    }

    public void clearAllResumeData() throws IOException {
        File dir = new File(getStoragePath());
        if (!dir.exists()) {
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                if (!file.delete()) {
                    System.err.println("Failed to delete file: " + file.getAbsolutePath());
                }
            }
        }
    }

    public int getResumeDataCount() {
        File dir = new File(getStoragePath());
        if (!dir.exists()) {
            return 0;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        return files != null ? files.length : 0;
    }

    public Set<String> getAllDatasetIds() {
        File datasetsDir = new File(getStoragePath(), "datasets");
        if (!datasetsDir.exists()) {
            return Collections.emptySet();
        }

        File[] files = datasetsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return Collections.emptySet();
        }

        Set<String> datasetIds = new HashSet<>();
        for (File file : files) {
            String fileName = file.getName().replace(".json", "");
            datasetIds.add(fileName);
        }
        return datasetIds;
    }

    // Add this method for dataset-specific storage
    public void storeDatasetResumeData(String datasetId, ResumeData resumeData) throws IOException {
        if (resumeData == null) {
            throw new IllegalArgumentException("Resume data cannot be null");
        }

        Path storageDir = Paths.get(getStoragePath(), "datasets");
        if (!Files.exists(storageDir)) {
            Files.createDirectories(storageDir);
        }

        resumeData.setId(datasetId);
        Path dataPath = storageDir.resolve(datasetId + ".json");
        objectMapper.writeValue(dataPath.toFile(), resumeData);

        System.out.println("Dataset " + datasetId + " stored at: " + dataPath.toString());
    }

    // Add this method for dataset-specific retrieval
    public ResumeData getDatasetResumeData(String datasetId) throws IOException {
        Path dataPath = Paths.get(getStoragePath(), "datasets", datasetId + ".json");
        File file = dataPath.toFile();

        if (!file.exists()) {
            System.out.println("No data found for dataset: " + datasetId);
            return null;
        }

        return objectMapper.readValue(file, ResumeData.class);
    }

    // Base Resume (PDF text) helpers
    public void saveBaseResumeText(String text) throws IOException {
        Path storageDir = Paths.get(getStoragePath());
        if (!Files.exists(storageDir)) {
            Files.createDirectories(storageDir);
        }
        Path path = storageDir.resolve("base_resume.txt");
        Files.writeString(path, text == null ? "" : text);
        System.out.println("Base resume text stored at: " + path);
    }

    public String getBaseResumeText() throws IOException {
        Path path = Paths.get(getStoragePath()).resolve("base_resume.txt");
        File f = path.toFile();
        if (!f.exists()) {
            System.out.println("Base resume text not found at: " + path);
            return null;
        }
        return Files.readString(path);
    }

    // Per-row resume data (bullet + tags)
    public void saveLatestResumeRows(java.util.List<com.joborchestratorai.akkajoborchestratorai.models.ResumeRow> rows) throws IOException {
        Path storageDir = Paths.get(getStoragePath());
        if (!Files.exists(storageDir)) {
            Files.createDirectories(storageDir);
        }
        Path path = storageDir.resolve("latest_rows.json");
        objectMapper.writeValue(path.toFile(), rows);
    }

    public java.util.List<com.joborchestratorai.akkajoborchestratorai.models.ResumeRow> getLatestResumeRows() throws IOException {
        Path path = Paths.get(getStoragePath()).resolve("latest_rows.json");
        File f = path.toFile();
        if (!f.exists()) {
            return java.util.Collections.emptyList();
        }
        com.fasterxml.jackson.core.type.TypeReference<java.util.List<com.joborchestratorai.akkajoborchestratorai.models.ResumeRow>> typeRef =
                new com.fasterxml.jackson.core.type.TypeReference<>() {};
        return objectMapper.readValue(f, typeRef);
    }
}
