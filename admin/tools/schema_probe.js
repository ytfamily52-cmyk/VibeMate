const url = 'https://firebaserules.googleapis.com/$discovery/rest?version=v1';
fetch(url)
    .then((r) => r.json())
    .then((d) => {
        const p = d.resources.projects;
        console.log('=== releases methods ===');
        console.log(Object.keys(p.resources.releases.methods));
        console.log('=== updaterelease ===');
        console.log(JSON.stringify(p.resources.releases.methods.patch, null, 1));
        console.log('=== Release schema ===');
        console.log(JSON.stringify(d.schemas.Release, null, 1));
        console.log('=== UpdateReleaseRequest schema ===');
        console.log(JSON.stringify(d.schemas.UpdateReleaseRequest, null, 1));
        console.log('=== Ruleset schema ===');
        console.log(JSON.stringify(d.schemas.Ruleset, null, 1).slice(0, 1200));
    });
