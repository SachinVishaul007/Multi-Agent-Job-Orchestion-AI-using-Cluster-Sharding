package com.joborchestratorai.akkajoborchestratorai.models;

import java.util.List;

public class ResumeRow {
    private String bulletText;
    private List<String> detectedTags;

    public ResumeRow() {}

    public ResumeRow(String bulletText, List<String> detectedTags) {
        this.bulletText = bulletText;
        this.detectedTags = detectedTags;
    }

    public String getBulletText() { return bulletText; }
    public void setBulletText(String bulletText) { this.bulletText = bulletText; }

    public List<String> getDetectedTags() { return detectedTags; }
    public void setDetectedTags(List<String> detectedTags) { this.detectedTags = detectedTags; }
}

