App.module "Jobs.List", (List, App, Backbone, Marionette, $, _) ->

  class List.Controller extends Marionette.Controller

    _showLoadingView: ->
      loadingView = new App.Common.Views.LoadingView(message: 'Loading jobs...')
      App.contentRegion.show(loadingView)

    _buildToolbar: (jobs) =>

      runTestJobButton = new App.Components.Toolbar.ButtonModel(
        title: 'Run test job'
        iconClass: 'icon-play'
        cssClasses: 'btn-info pull-right'
        event: 'run:test:job'
      )

      stateFilter = new App.Components.Toolbar.FilterModel(
        title: 'State'
        event: 'state:filter:changed'
        menuItems: new App.Components.Toolbar.FilterItemsCollection([
          new App.Components.Toolbar.FilterItemModel(title: 'Queued')
          new App.Components.Toolbar.FilterItemModel(title: 'Running')
          new App.Components.Toolbar.FilterItemModel(title: 'Complete')
          new App.Components.Toolbar.FilterItemModel(title: 'Failed')
        ])
      )

      workers = _.unique(_.map(jobs.models, (job) -> job.get('worker_type')))
      workerFilter = new App.Components.Toolbar.FilterModel(
        title: 'Worker'
        event: 'workers:filter:changed'
        menuItems: new App.Components.Toolbar.FilterItemsCollection(
          _.map(workers, (worker) ->
            new App.Components.Toolbar.FilterItemModel(title: worker)
          )
        )
      )

      toolbarItems = new App.Components.Toolbar.ItemsCollection()
      toolbarItems.add(runTestJobButton)
      toolbarItems.add(stateFilter)
      toolbarItems.add(workerFilter)
      toolbarController = new App.Components.Toolbar.Controller()
      return toolbarController.createToolbar(toolbarItems)


    _buildGrid: (jobs) ->
      gridModel = new App.Components.Grid.Model(
        items: jobs
        paginated: true
        emptyView: App.Jobs.List.NoJobsView
        backgrid:
          row: App.Jobs.List.FlashingRow
          columns: [
            name: 'id'
            label: '#'
            editable: false
            cell: List.JobIDCell
          ,
            name: 'worker_type'
            label: 'Worker'
            editable: false
            cell: Backgrid.StringCell.extend(className: 'monospace')
          ,
            name: 'enqueued_at'
            label: 'Enqueued'
            editable: false
            cell: Backgrid.Extension.MomentCell.extend(
              displayFormat: 'MMMM Do YYYY, h:mm:ss a'
            )
          ,
            name: 'state'
            label: 'State'
            editable: false
            cell: Backgrid.StringCell.extend(className: 'capitalised')
          ,
            name: 'progress'
            label: 'Progress'
            editable: false
            cell: App.Jobs.List.ProgressCell
          ]
      )
      gridController = new App.Components.Grid.Controller()
      return gridController.createGrid(gridModel)

    _updateJob: (jobData, jobs) ->
      updatedJob = new App.Entities.Job(jobData)
      jobs.get(updatedJob.id).set(updatedJob.attributes)

    _runTestJob: (jobs, layout) ->
      testJob = new App.Entities.Job(
        worker_type: 'oncue.worker.TestWorker'
      )
      savingJob = App.request('job:entity:save', testJob)
      $.when(savingJob).done( (job) ->
        job.set('is_new', true)
        jobs.add(job)
        jobs.fullCollection.sort()
      )
      $.when(savingJob).fail( (job, xhr) ->
        errorView = new App.Common.Views.ErrorView(
          message: "Failed to kick off a test job (#{xhr.statusText})"
        )
        layout.errorRegion.show(errorView)
      )

    listJobs: ->
      @_showLoadingView()
      layout = new List.Layout()
      fetchingJobs = App.request('job:entities')
      $.when(fetchingJobs).done( (jobs) =>
        grid = @_buildGrid(jobs)

        toolbar = @_buildToolbar(jobs)
        toolbar.on('run:test:job', =>
          @_runTestJob(jobs, layout)
        )
        toolbar.on('state:filter:changed', (filterItems) ->
          # TODO Filtering logic!
        )
        toolbar.on('worker:filter:changed', (filterItems) ->
          # TODO Filtering logic!
        )

        App.vent.on('run:test:job', =>
          @_runTestJob(jobs, layout)
        )

        App.vent.on('job:progressed', (jobData) =>
          @_updateJob(jobData, jobs)
        )

        layout.on('show', ->
          layout.toolbarRegion.show(toolbar)
          layout.jobsRegion.show(grid)
        )

        App.contentRegion.show(layout)
      )

  List.addInitializer ->
    List.controller = new List.Controller()