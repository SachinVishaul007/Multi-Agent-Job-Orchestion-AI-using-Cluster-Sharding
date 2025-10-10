# Akka Job Orchestrator AI

## Project Proposal: AI-Powered Job Application Pipeline with Akka Cluster Sharding

---

### Summary

Built a smart job application system that makes job hunting less painful and more effective. Our platform uses **Akka Cluster Sharding** to run multiple AI agents that work together—they research companies, customize resumes to match specific job descriptions and company cultures, reach out to recruiters, and track where each application stands.

When you apply for a job, the system:
- Reads the job posting and company profile.
- Tweaks your resume to highlight the most relevant skills and experiences.
- Keeps tabs on every application you've sent.
- Reminds you to follow up.
- Shows you real-time updates on your progress.

We're combining **RAG technology** with an **actor-based architecture** to create something that's both technically impressive and actually useful for job seekers.

#### Behind the Scenes

- **Cluster sharding** distributes the workload—every job application, company profile, and recruiter conversation runs as its own sharded entity.
- The system is highly scalable and can handle thousands of users simultaneously.
- If one server goes down, the others pick up the slack without losing any data.

---

## Team Composition

**Project Type:** Team Project (2 members)

**Team Members:**
- Sachin Vishaul Baskar
- Saurabh

---
