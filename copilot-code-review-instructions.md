GitHub Copilot code review instructions
Review every pull request update when a new push is made.
If this PR has not been reviewed before, write a review for the entire PR, including the complete diff and the overall change set.
If a prior review already exists for this PR, review only the changes introduced since the last review / latest push, while noting any previously identified issues that remain unresolved.
Be explicit about whether the review is for the full PR or only the new changes.
Focus on correctness, security, reliability, performance, maintainability, and test coverage.
Prioritize blocking issues that could break production, cause regressions, or introduce security vulnerabilities.
Provide actionable, specific comments with file paths and, when helpful, relevant code references.
Avoid low-value style-only feedback unless it materially affects code quality or maintainability.
If no material issues are found, say "No material issues found." and do not invent problems.
Do not approve the PR when there are unresolved high-risk concerns.
Use a structured review summary that clearly states the review scope, findings, and approval status.
Flag any regression risk, compatibility issues, missing validation, or untested behavior.
Keep comments factual, actionable, and tied to the code under review.
