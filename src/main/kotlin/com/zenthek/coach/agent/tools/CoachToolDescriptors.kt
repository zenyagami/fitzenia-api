package com.zenthek.coach.agent.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType

object CoachToolDescriptors {

    val ALL: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "getUserProfile",
            description = "Returns the user's profile: name, sex, height in cm, and age. Use for anything that depends on who the user is (BMI, healthy weight range, ideal weight, protein per kg).",
        ),
        ToolDescriptor(
            name = "getUserGoal",
            description = "Returns the user's configured goal: goal direction (lose/maintain/gain), target phase, goal weight in kg, pace tier, activity level, body fat estimate, protein preference, lifting experience. Use for ideal-weight, goal-progress, and pace questions.",
        ),
        ToolDescriptor(
            name = "getCurrentTargets",
            description = "Returns the user's current daily targets: target calories (with min/max range), protein/carbs/fat grams, estimated TDEE and BMR, TDEE mode/confidence, pace tier. Use to reason about deficits/surpluses and remaining macros.",
        ),
        ToolDescriptor(
            name = "getTodayMacros",
            description = "Returns calories and macros consumed today plus the item count. Server injects the current date.",
        ),
        ToolDescriptor(
            name = "getRecentWeight",
            description = "Returns weight log entries over the last N days ordered newest-first.",
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    name = "days",
                    description = "Number of days to look back (default 14)",
                    type = ToolParameterType.Integer,
                ),
            ),
        ),
        ToolDescriptor(
            name = "getRecentSteps",
            description = "Returns daily step count log entries over the last N days ordered newest-first. Steps sync automatically from the user's phone; a missing date means no count was logged that day, not an error. Use for step-count and daily-activity questions (e.g. \"how many steps yesterday\", \"was I active this week\").",
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    name = "days",
                    description = "Number of days to look back (default 14)",
                    type = ToolParameterType.Integer,
                ),
            ),
        ),
        ToolDescriptor(
            name = "getCurrentPhase",
            description = "Returns the user's active diet/training phase: target phase, goal direction, pace tier, start date, goal date, days into phase, plus start/target weight and start/target body-fat percent. Use for phase-progress and time-to-goal questions.",
        ),
        ToolDescriptor(
            name = "getUserCoachNotes",
            description = "Returns the user's saved coach notes (preferences, reminders, personal facts). Up to 10 most recent.",
        ),
        ToolDescriptor(
            name = "writeUserCoachNote",
            description = "Saves a preference, restriction, or personal fact about the user so it persists across future chats. Use when the user reveals something durable (e.g. 'I'm vegetarian', 'I hate oatmeal', 'my goal is to run a 5K'). Returns {id} on success.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    name = "category",
                    description = "One of: preference, restriction, goal_context, other",
                    type = ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    name = "note",
                    description = "The note content. Max 500 characters.",
                    type = ToolParameterType.String,
                ),
            ),
        ),
        ToolDescriptor(
            name = "getWeightTrend",
            description = "Returns weight trend statistics over the last N weeks: start/end weight, exponential moving average, slope in kg/week, and — when the user logs body fat — start/end body-fat percent and body-fat slope per week. Use for progress checks and time-to-goal projections.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    name = "weeks",
                    description = "Number of weeks to analyse",
                    type = ToolParameterType.Integer,
                ),
            ),
        ),
        ToolDescriptor(
            name = "getDiaryForDate",
            description = "Returns all food diary entries for a specific date grouped by meal type.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    name = "date",
                    description = "Date in YYYY-MM-DD format",
                    type = ToolParameterType.String,
                ),
            ),
        ),
        ToolDescriptor(
            name = "searchDiary",
            description = "Searches the user's ENTIRE food diary history by food or meal name and returns every match newest-first with the date it was logged. Use this for any \"when did I / how often do I / when did I last eat X\" question. Never guess dates one at a time with getDiaryForDate — search here instead, then use getDiaryForDate only if the user asks what else they ate on a specific day.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    name = "query",
                    description = "Food or meal name to look for, e.g. \"bibimbap\". Case-insensitive, partial matches count.",
                    type = ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    name = "days",
                    description = "How many days back to search (default 365 — a full year)",
                    type = ToolParameterType.Integer,
                ),
                ToolParameterDescriptor(
                    name = "limit",
                    description = "Maximum entries to return (default 50, capped at 100)",
                    type = ToolParameterType.Integer,
                ),
            ),
        ),
        ToolDescriptor(
            name = "getBodyMeasurements",
            description = "Returns the user's body-measurement history (waist, chest, hips, neck, shoulders, biceps, thighs) over the last N days: the latest value in cm, the earliest value in the window, and the change between them. Use for body-recomposition questions, and whenever scale weight has stalled — circumferences often keep moving when weight does not.",
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    name = "days",
                    description = "Number of days to look back (default 180)",
                    type = ToolParameterType.Integer,
                ),
            ),
        ),
        ToolDescriptor(
            name = "getPastPhases",
            description = "Returns the user's COMPLETED diet/training phases newest-first: phase, goal direction, pace, start and end date, duration in weeks, start/end weight and body fat, total weight change and kg per week. Use to compare the current phase against the user's own history (e.g. \"is this cut slower than my last one?\") instead of against generic norms. getCurrentPhase returns only the active phase.",
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    name = "limit",
                    description = "Maximum phases to return (default 5, capped at 20)",
                    type = ToolParameterType.Integer,
                ),
            ),
        ),
        ToolDescriptor(
            name = "searchKnowledgeBase",
            description = "Searches the Fitzenia knowledge base for nutrition, training, and app information. Returns ranked chunks with source citations.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    name = "query",
                    description = "Natural-language search query",
                    type = ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    name = "sections",
                    description = "Comma-separated section filter: app, nutrition, training, recipes, general",
                    type = ToolParameterType.String,
                ),
            ),
        ),
    )

    /** Pro retries may read user data but must never re-invoke the only write tool. */
    val READ_ONLY: List<ToolDescriptor> = ALL.filter { it.name != "writeUserCoachNote" }
}
