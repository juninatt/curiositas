# Changelog

## [0.6.0](https://github.com/juninatt/curiositas/compare/v0.5.1...v0.6.0) (2026-11-02)


### Features

* **api:** answer 410 gone for deleted persons ([1b5b46e](https://github.com/juninatt/curiositas/commit/1b5b46edd383d3f9fe81d901733d176b3d598550))
* **api:** show the revisions of a person ([c1aab10](https://github.com/juninatt/curiositas/commit/c1aab10ab8594a43c8a31de67deaa4616e3e9f4a))
* **data:** record imported changes in the revision log ([3aafa45](https://github.com/juninatt/curiositas/commit/3aafa453f2360cf9683f9960562e452f341f12de))
* **data:** refuse to start when a person has no history ([0722020](https://github.com/juninatt/curiositas/commit/0722020988d273e43d751e90a6d64a37fa0e06b2))
* **person:** record every change to a person in a revision log ([724b7c0](https://github.com/juninatt/curiositas/commit/724b7c0a0bd339117f3a121263d6ebba82d2da75))

## [0.5.1](https://github.com/juninatt/curiositas/compare/v0.5.0...v0.5.1) (2026-11-02)


### Bug Fixes

* **api:** always label successful responses as application/json ([3fd72b7](https://github.com/juninatt/curiositas/commit/3fd72b7a652e3e1547e4623302d505d6c38e883e))
* **api:** describe problem type and instance as uri references ([647fcb6](https://github.com/juninatt/curiositas/commit/647fcb6476277a121f32f3ffeec1f97e910c4372))
* **api:** document 406 when no requested media type can be served ([188d713](https://github.com/juninatt/curiositas/commit/188d71314115eda50c86691a90e7c959f934714d))
* **api:** let any client delete regardless of its accept header ([6412fc3](https://github.com/juninatt/curiositas/commit/6412fc3bf6670cdfdfc2eb1e04926f5ad465c896))

## [0.5.0](https://github.com/juninatt/Curiositas/compare/v0.4.0...v0.5.0) (2026-10-20)


### Features

* **data:** export all persons as json files ([e60931f](https://github.com/juninatt/Curiositas/commit/e60931f530475c3f57b2c5c56d2e0e084c4db8cb))
* **data:** rebuild the database from json files ([38409ff](https://github.com/juninatt/Curiositas/commit/38409ff55e51f9e8b453ee481072a4c7053d0dcc))
* **data:** refuse to start when the database and the files differ ([ebe2840](https://github.com/juninatt/Curiositas/commit/ebe28402563c53b212a7bf3a172eeb20937b383f))
* **data:** write the json files with every change ([2c300f8](https://github.com/juninatt/Curiositas/commit/2c300f8a474ba55b32142ef55dfe6c7af0e6dc72))
* **person:** reject duplicate persons with 409 ([3f83c06](https://github.com/juninatt/Curiositas/commit/3f83c0621f6bdee4d482bf251ef691445874dbfc))

## [0.4.0](https://github.com/juninatt/Curiositas/compare/v0.3.0...v0.4.0) (2026-10-08)


### Features

* **api:** show the openapi contract in swagger ui ([1dec6a7](https://github.com/juninatt/Curiositas/commit/1dec6a7c424bc25114aaa1747c424ac824178f72))
* **person:** add endpoint to replace a person, guarded by If-Match ([ff9991f](https://github.com/juninatt/Curiositas/commit/ff9991f1bd61e909ba4adea82a7e271157b12352))
* **person:** add endpoints to create and delete persons ([b204d33](https://github.com/juninatt/Curiositas/commit/b204d33fd24dd2883f23c67e597739f245947be5))
* **person:** add endpoints to list persons and get a person by id ([d4ecacd](https://github.com/juninatt/Curiositas/commit/d4ecacdeef04a4d92a841d71311747350678ebe9))
* **person:** store persons in the database ([ab0d639](https://github.com/juninatt/Curiositas/commit/ab0d639e5cb2b2ea31bc97523a0e3a264f7e847b))
* **security:** require admin login for creating, replacing and deleting data ([6b3598a](https://github.com/juninatt/Curiositas/commit/6b3598aae4ac5ba12a578a14686ec65e583f1e13))
* **web:** add a read-only preview of persons as start page ([4583d92](https://github.com/juninatt/Curiositas/commit/4583d923a0e6540c2085942bb5c6a715a1a065c7))

## [0.3.0](https://github.com/juninatt/Curiositas/compare/v0.2.0...v0.3.0) (2026-10-08)


### Features

* **date:** calculate age range between birth and death ([b7f8f15](https://github.com/juninatt/Curiositas/commit/b7f8f151b229714b9cf7b6be1f2cd15f2d97f3d1))

## [0.2.0](https://github.com/juninatt/Curiositas/compare/v0.1.0...v0.2.0) (2026-10-08)


### Features

* **date:** derive display text for historical dates ([86949f0](https://github.com/juninatt/Curiositas/commit/86949f0e41b8b11d41610d9d9974d95115f30423))

## 0.1.0 (2026-10-08)


### Features

* **date:** add historical date with optional month, day and uncertainty ([5c49132](https://github.com/juninatt/Curiositas/commit/5c49132397ebfdd32f120341d62c7f280eb96129))
