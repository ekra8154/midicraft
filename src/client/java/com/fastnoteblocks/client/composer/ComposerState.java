package com.fastnoteblocks.client.composer;

import com.fastnoteblocks.client.FastNoteblocksConfig;

/**
 * Everything about a composition that an edit can change, and therefore everything undo has to
 * restore.
 *
 * <p>The timescale is part of this rather than a loose setting because it changes how the
 * composition sounds and how it builds, exactly like moving a note does. Keeping it outside the
 * history left one edit in the editor that could not be taken back.</p>
 */
public record ComposerState(ComposerProject project, int delayScaleQuarters) {
	public ComposerState {
		project = project == null ? ComposerProject.empty("Untitled sequence") : project;
		delayScaleQuarters = FastNoteblocksConfig.clampSequenceDelayScale(delayScaleQuarters);
	}

	public ComposerState withProject(ComposerProject value) {
		return new ComposerState(value, delayScaleQuarters);
	}

	public ComposerState withDelayScaleQuarters(int value) {
		return new ComposerState(project, value);
	}
}
