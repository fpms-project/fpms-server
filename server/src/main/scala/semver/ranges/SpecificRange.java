package semver.ranges;

import semver.Version;

public class SpecificRange extends Range {

	public SpecificRange(String version) {
		this(new Version(version));
	}

	public SpecificRange(Version version) {
		super(version, version, true, true);
	}
}
